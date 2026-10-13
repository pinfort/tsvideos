package me.pinfort.tsvideos.core.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.component.ProcessingRetry
import me.pinfort.tsvideos.core.component.ProcessingRetryRuntime
import me.pinfort.tsvideos.core.config.ProcessingRetryConfigurationProperties
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.UploadManifestMapper
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.core.external.samba.VerifiedUploadNas
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.SQLTransientConnectionException
import java.util.UUID

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class VerifiedUploadCommandTest : ExpectSpec() {
    @Autowired private lateinit var manifests: UploadManifestMapper

    @Autowired private lateinit var files: CreatedFileMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager

    init {
        data class Fixture(
            val command: VerifiedUploadCommand,
            val nas: VerifiedUploadNas,
            val source: File,
            val remote: MutableMap<String, ByteArray>,
            val job: Long,
            val target: String,
        )

        fun fixture(
            checksum: Boolean = true,
            mapper: UploadManifestMapper = manifests,
        ): Fixture {
            val nas = mockk<VerifiedUploadNas>()
            val remote = mutableMapOf<String, ByteArray>()
            every { nas.exists(any(), any()) } answers { remote.containsKey(secondArg()) }
            every { nas.size(any(), any()) } answers { remote.getValue(secondArg()).size.toLong() }
            every { nas.sha256(any(), any()) } answers {
                MessageDigest.getInstance("SHA-256").digest(remote.getValue(secondArg())).joinToString("") { "%02x".format(it) }
            }
            every { nas.transfer(any(), any(), any(), any(), any()) } answers {
                val path = secondArg<String>()
                check(!remote.containsKey(path))
                arg<() -> Unit>(3)()
                remote[path] = arg<File>(2).readBytes()
            }
            every { nas.finalize(any(), any(), any()) } answers {
                check(!remote.containsKey(thirdArg()))
                remote[thirdArg()] = remote.remove(secondArg())!!
            }
            every { nas.delete(any(), any()) } answers {
                remote.remove(secondArg())
                Unit
            }
            val created = CreatedFileCommand(files, mockk(), mockk(), mockk(relaxed = true))
            val source = Files.createTempFile("verified-upload-", ".mp4").toFile().apply { writeText("complete video") }
            val job = UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE)
            return Fixture(
                VerifiedUploadCommand(
                    mapper,
                    nas,
                    created,
                    files,
                    transactions,
                    checksum,
                    ProcessingRetry(
                        ProcessingRetryConfigurationProperties(3, 1, 1),
                        mockk<ProcessingRetryRuntime> {
                            every { random() } returns 0.0
                            every { pause(any()) } returns Unit
                        },
                    ),
                ),
                nas,
                source,
                remote,
                job,
                "test/${UUID.randomUUID()}/video.mp4",
            )
        }

        fun Fixture.upload() = command.upload(job, "output", source, SambaClient.NasType.VIDEO_STORE_NAS, target)

        fun Fixture.manifest() = checkNotNull(manifests.find(job, ProcessingIdentity.hash("output")))

        expect("transient partial transfer retries owned temp and converges real receipt") {
            val f = fixture()
            var transfers = 0
            every { f.nas.transfer(any(), any(), any(), any(), any()) } answers {
                arg<() -> Unit>(3)()
                if (++transfers == 1) {
                    f.remote[secondArg()] = byteArrayOf(1)
                    throw SocketTimeoutException()
                }
                f.remote[secondArg()] = f.source.readBytes()
            }
            try {
                val receipt = f.upload()
                transfers shouldBe 2
                f.manifest().state shouldBe "FINALIZED"
                f.manifest().claimToken shouldBe null
                f.remote.keys shouldBe setOf(f.target)
                f.command.register(receipt, 876544, "video/mp4", null)
                f.command.verifyRegistered(f.job, "output") shouldBe receipt
                f.source.exists() shouldBe true
            } finally {
                f.source.delete()
            }
        }
        expect("transient DB registration rolls back then converges one created row") {
            var registrations = 0
            val wrapped =
                object : UploadManifestMapper by manifests {
                    override fun register(
                        id: Long,
                        token: String,
                        createdFileId: Long,
                    ): Int {
                        if (++registrations == 1) throw SQLTransientConnectionException()
                        return manifests.register(id, token, createdFileId)
                    }
                }
            val f = fixture(mapper = wrapped)
            try {
                val receipt = f.upload()
                val registered = f.command.register(receipt, 876545, "video/mp4", null)
                registrations shouldBe 2
                f.command.register(receipt, 876545, "video/mp4", null).id shouldBe registered.id
                files.selectBySplittedFileId(876545).count { it.file == f.target } shouldBe 1
                f.manifest().claimToken shouldBe null
                f.source.exists() shouldBe true
            } finally {
                f.source.delete()
            }
        }
        expect("registered read timeout retries without another row or source removal") {
            val f = fixture()
            try {
                val receipt = f.upload()
                val registered = f.command.register(receipt, 876546, "video/mp4", null)
                var reads = 0
                every { f.nas.size(any(), f.target) } answers {
                    if (++reads == 1) throw SocketTimeoutException()
                    f.source.length()
                }
                f.command.verifyRegistered(f.job, "output") shouldBe receipt
                reads shouldBe 2
                files.find(registered.id)?.id shouldBe registered.id
                f.source.exists() shouldBe true
            } finally {
                f.source.delete()
            }
        }
        expect("ambiguous create or rename timeout never retries") {
            listOf(false, true).forEach { rename ->
                val f = fixture()
                val failure = SocketTimeoutException()
                if (rename) {
                    every { f.nas.finalize(any(), any(), any()) } throws failure
                } else {
                    every { f.nas.transfer(any(), any(), any(), any(), any()) } throws failure
                }
                try {
                    shouldThrow<SocketTimeoutException> { f.upload() } shouldBe failure
                    f.manifest().state shouldBe if (rename) "FINALIZING" else "CREATING"
                    f.manifest().claimToken shouldBe null
                    verify(exactly = 1) { f.nas.transfer(any(), any(), any(), any(), any()) }
                    f.source.exists() shouldBe true
                } finally {
                    f.source.delete()
                }
            }
        }
        expect("cleanup failure blocks further attempts and retains primary cleanup error") {
            val f = fixture()
            val cleanup = SocketTimeoutException("cleanup")
            every { f.nas.transfer(any(), any(), any(), any(), any()) } answers {
                arg<() -> Unit>(3)()
                f.remote[secondArg()] = byteArrayOf(1)
                throw SocketTimeoutException("transfer")
            }
            every { f.nas.delete(any(), any()) } throws cleanup
            try {
                shouldThrow<SocketTimeoutException> { f.upload() } shouldBe cleanup
                cleanup.suppressed.size shouldBe 1
                verify(exactly = 1) {
                    f.nas.transfer(any(), any(), any(), any(), any())
                    f.nas.delete(any(), any())
                }
                f.source.exists() shouldBe true
            } finally {
                f.source.delete()
            }
        }
        expect("failed claim release preserves transfer exception and forbids retry") {
            val release = SQLTransientConnectionException("release")
            val wrapped =
                object : UploadManifestMapper by manifests {
                    override fun release(
                        id: Long,
                        token: String,
                    ): Int = throw release
                }
            val f = fixture(mapper = wrapped)
            val primary = SocketTimeoutException("transfer")
            every { f.nas.transfer(any(), any(), any(), any(), any()) } answers {
                arg<() -> Unit>(3)()
                throw primary
            }
            try {
                shouldThrow<SocketTimeoutException> { f.upload() } shouldBe primary
                primary.suppressed.single() shouldBe release
                verify(exactly = 1) { f.nas.transfer(any(), any(), any(), any(), any()) }
                (f.manifest().claimToken != null) shouldBe true
                f.source.exists() shouldBe true
            } finally {
                f.source.delete()
            }
        }
        expect("zero row claim release after success fails without retry") {
            val wrapped =
                object : UploadManifestMapper by manifests {
                    override fun release(
                        id: Long,
                        token: String,
                    ): Int = 0
                }
            val f = fixture(mapper = wrapped)
            try {
                shouldThrow<IllegalStateException> { f.upload() }.suppressed.size shouldBe 1
                verify(exactly = 1) { f.nas.transfer(any(), any(), any(), any(), any()) }
                f.source.exists() shouldBe true
            } finally {
                f.source.delete()
            }
        }
        expect("retire removes owned row without release but failed removal still releases") {
            val f = fixture()
            try {
                f.upload()
                f.remote.clear()
                f.command.retireAfterReset(f.job)
                manifests.find(f.job, ProcessingIdentity.hash("output")) shouldBe null
            } finally {
                f.source.delete()
            }
            val failure = SQLTransientConnectionException()
            val wrapped =
                object : UploadManifestMapper by manifests {
                    override fun remove(
                        id: Long,
                        token: String,
                    ): Int = throw failure
                }
            val g = fixture(mapper = wrapped)
            try {
                g.upload()
                g.remote.clear()
                shouldThrow<SQLTransientConnectionException> { g.command.retireAfterReset(g.job) } shouldBe failure
                g.manifest().claimToken shouldBe null
                g.source.exists() shouldBe true
            } finally {
                g.source.delete()
            }
        }

        expect("interruption cleans only an owned partial upload and releases its claim") {
            val f = fixture()
            every { f.nas.transfer(any(), any(), any(), any(), any()) } answers {
                arg<() -> Unit>(3)()
                f.remote[secondArg()] = byteArrayOf(1)
                throw InterruptedException()
            }
            try {
                shouldThrow<InterruptedException> { f.upload() }
                f.remote.isEmpty() shouldBe true
                f.source.exists() shouldBe true
                f.manifest().state shouldBe "NEW"
                f.manifest().claimToken shouldBe null
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("interruption after rename preserves ambiguous remote ownership") {
            val f = fixture()
            every { f.nas.finalize(any(), any(), any()) } answers {
                f.remote[f.target] = f.remote.remove(secondArg())!!
                throw InterruptedException()
            }
            try {
                shouldThrow<InterruptedException> { f.upload() }
                f.remote.keys shouldBe setOf(f.target)
                f.manifest().state shouldBe "FINALIZING"
                f.source.exists() shouldBe true
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("successful upload is registered exactly once and repeated callback survives local deletion") {
            val f = fixture()
            try {
                val receipt = f.upload()
                f.source.exists() shouldBe true
                f.manifest().state shouldBe "FINALIZED"
                val first = f.command.register(receipt, 876543, "video/mp4", null)
                f.command.register(receipt, 876543, "video/mp4", null).id shouldBe first.id
                f.command.deleteLocal(receipt)
                f.source.exists() shouldBe false
                f.upload() shouldBe receipt
                f.command.register(receipt, 876543, "video/mp4", null).id shouldBe first.id
                f.remote.keys shouldBe setOf(f.target)
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("truncated transfer preserves local source and retry replaces only owned temporary artifact") {
            val f = fixture()
            every { f.nas.transfer(any(), any(), any(), any(), any()) } answers {
                arg<() -> Unit>(3)()
                f.remote[secondArg()] = byteArrayOf(1)
            }
            try {
                shouldThrow<IllegalStateException> { f.upload() }
                f.manifest().state shouldBe "TRANSFERRING"
                f.source.exists() shouldBe true
                f.remote.containsKey(f.target) shouldBe false
                every { f.nas.transfer(any(), any(), any(), any(), any()) } answers {
                    arg<() -> Unit>(3)()
                    f.remote[secondArg()] = f.source.readBytes()
                }
                f.upload()
                f.manifest().state shouldBe "FINALIZED"
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("same sized unrelated final file never grants ownership") {
            val f = fixture()
            try {
                f.remote[f.target] = f.source.readBytes()
                shouldThrow<IllegalStateException> { f.upload() }
                f.manifest().state shouldBe "NEW"
                f.remote.keys shouldBe setOf(f.target)
                f.source.exists() shouldBe true
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("checksum mismatch or unavailable readback prevents finalization") {
            listOf(false, true).forEach { unsupported ->
                val f = fixture()
                try {
                    if (unsupported) {
                        every { f.nas.sha256(any(), any()) } throws UnsupportedOperationException("unsupported")
                    } else {
                        every { f.nas.sha256(any(), any()) } returns "bad"
                    }
                    shouldThrow<Exception> { f.upload() }
                    f.manifest().state shouldBe "TRANSFERRING"
                    f.remote.containsKey(f.target) shouldBe false
                    f.source.exists() shouldBe true
                } finally {
                    f.source.delete()
                }
            }
        }
        expect("rename with ambiguous acknowledgement never auto accepts final destination") {
            val f = fixture()
            every { f.nas.finalize(any(), any(), any()) } answers {
                f.remote[thirdArg()] = f.remote.remove(secondArg())!!
                error("connection lost")
            }
            try {
                shouldThrow<IllegalStateException> { f.upload() }
                f.manifest().state shouldBe "FINALIZING"
                shouldThrow<IllegalStateException> { f.upload() }
                f.source.exists() shouldBe true
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("DB registration failure rolls back registration and preserves receipt for retry") {
            val f = fixture()
            try {
                val receipt = f.upload()
                val failing =
                    CreatedFileCommand(
                        mockk {
                            every { insert(any(), any(), any(), any(), any(), any(), any()) } throws
                                IllegalStateException("db")
                        },
                        mockk(),
                        mockk(),
                        mockk(relaxed = true),
                    )
                val command = VerifiedUploadCommand(manifests, f.nas, failing, files, transactions)
                shouldThrow<IllegalStateException> { command.register(receipt, 1, "video/mp4", null) }
                f.manifest().state shouldBe "FINALIZED"
                shouldThrow<IllegalStateException> { command.deleteLocal(receipt) }
                f.source.exists() shouldBe true
                f.command.register(receipt, 1, "video/mp4", null)
                f.command.deleteLocal(receipt)
                f.source.exists() shouldBe false
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("changed source and missing remote block deletion even after registration") {
            val f = fixture()
            try {
                val receipt = f.upload()
                f.command.register(receipt, 1, "video/mp4", null)
                f.source.writeText("replacement")
                shouldThrow<IllegalStateException> { f.command.deleteLocal(receipt) }
                f.source.exists() shouldBe true
                f.remote.remove(f.target)
                shouldThrow<Exception> { f.command.deleteLocal(receipt) }
                f.source.exists() shouldBe true
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("cleanup failure preserves ownership and local data") {
            val f = fixture()
            every { f.nas.transfer(any(), any(), any(), any(), any()) } answers {
                arg<() -> Unit>(3)()
                f.remote[secondArg()] = byteArrayOf(1)
                error("interrupted")
            }
            try {
                shouldThrow<IllegalStateException> { f.upload() }
                every { f.nas.delete(any(), any()) } throws IllegalStateException("cleanup failure")
                shouldThrow<IllegalStateException> { f.upload() }
                f.manifest().state shouldBe "TRANSFERRING"
                f.source.exists() shouldBe true
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("uncertain exclusive open is never deleted as owned") {
            val f = fixture()
            every { f.nas.transfer(any(), any(), any(), any(), any()) } throws IllegalStateException("open outcome unknown")
            try {
                shouldThrow<IllegalStateException> { f.upload() }
                f.manifest().state shouldBe "CREATING"
                shouldThrow<IllegalStateException> { f.command.retireAfterReset(f.job) }
                shouldThrow<IllegalStateException> { f.upload() }
                f.source.exists() shouldBe true
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("simultaneous commands transfer once and leave one durable artifact") {
            val f = fixture()
            val starts = java.util.concurrent.CountDownLatch(1)
            val pool =
                java.util.concurrent.Executors
                    .newFixedThreadPool(4)
            val transfers =
                java.util.concurrent.atomic
                    .AtomicInteger()
            every { f.nas.transfer(any(), any(), any(), any(), any()) } answers {
                transfers.incrementAndGet()
                arg<() -> Unit>(3)()
                f.remote[secondArg()] = f.source.readBytes()
            }
            try {
                val futures =
                    (1..4).map {
                        pool.submit(
                            java.util.concurrent.Callable {
                                starts.await()
                                runCatching { f.upload() }
                            },
                        )
                    }
                starts.countDown()
                val results = futures.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                results.any { it.isSuccess } shouldBe true
                transfers.get() shouldBe 1
                f.manifest().state shouldBe "FINALIZED"
                f.manifest().claimToken shouldBe null
                f.source.exists() shouldBe true
            } finally {
                pool.shutdownNow()
                f.source.delete()
            }
        }
        expect("local delete failure retains registered receipt and can be retried") {
            val f = fixture()
            val directory = Files.createTempDirectory("delete-protection-")
            val protectedSource = directory.resolve("source.mp4").toFile()
            f.source.copyTo(protectedSource)
            val windows = System.getProperty("os.name").startsWith("Windows")
            val permissions = if (!windows) Files.getPosixFilePermissions(directory) else null
            try {
                val receipt = f.command.upload(f.job, "protected", protectedSource, SambaClient.NasType.VIDEO_STORE_NAS, f.target)
                f.command.register(receipt, 1, "video/mp4", null)
                if (windows) {
                    check(protectedSource.setReadOnly())
                } else {
                    Files.setPosixFilePermissions(
                        directory,
                        setOf(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                        ),
                    )
                }
                shouldThrow<java.io.IOException> { f.command.deleteLocal(receipt) }
                manifests.get(receipt.manifestId)?.state shouldBe "REGISTERED"
                protectedSource.exists() shouldBe true
                if (windows) check(protectedSource.setWritable(true)) else Files.setPosixFilePermissions(directory, permissions)
                f.command.deleteLocal(receipt)
                protectedSource.exists() shouldBe false
            } finally {
                if (windows) protectedSource.setWritable(true) else Files.setPosixFilePermissions(directory, permissions)
                protectedSource.delete()
                directory.toFile().delete()
                f.source.delete()
            }
        }
        expect("reset refuses unregistered final artifacts and retires only confirmed absent destinations") {
            val f = fixture()
            try {
                val receipt = f.upload()
                shouldThrow<IllegalStateException> { f.command.validateReset(f.job) }
                f.command.register(receipt, 1, "video/mp4", null)
                f.command.validateReset(f.job)
                shouldThrow<IllegalStateException> { f.command.retireAfterReset(f.job) }
                f.remote.remove(f.target)
                files.delete(checkNotNull(f.manifest().createdFileId))
                f.command.retireAfterReset(f.job)
                manifests.get(receipt.manifestId) shouldBe null
                f.source.exists() shouldBe true
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("explicit cleanup removes only the durable owned temporary file") {
            val f = fixture()
            every { f.nas.transfer(any(), any(), any(), any(), any()) } answers {
                arg<() -> Unit>(3)()
                f.remote[secondArg()] = byteArrayOf(1)
                error("interrupted")
            }
            try {
                shouldThrow<IllegalStateException> { f.upload() }
                f.remote["unrelated.upload-file"] = byteArrayOf(2)
                f.command.cleanupTemporary(f.job, "output")
                f.manifest().state shouldBe "NEW"
                f.remote.keys shouldBe setOf("unrelated.upload-file")
                f.source.exists() shouldBe true
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("manifest update failure rolls back a newly inserted created_file") {
            val f = fixture()
            try {
                val receipt = f.upload()
                val failing = mockk<UploadManifestMapper>()
                every { failing.claim(any(), any()) } answers { manifests.claim(firstArg(), secondArg()) }
                every { failing.get(any()) } answers { manifests.get(firstArg()) }
                every { failing.release(any(), any()) } answers { manifests.release(firstArg(), secondArg()) }
                every { failing.register(any(), any(), any()) } returns 0
                val created = CreatedFileCommand(files, mockk(), mockk(), mockk(relaxed = true))
                val command = VerifiedUploadCommand(failing, f.nas, created, files, transactions)
                val splitId = f.job
                shouldThrow<IllegalStateException> { command.register(receipt, splitId, "video/mp4", null) }
                files.selectBySplittedFileId(splitId).size shouldBe 0
                f.manifest().state shouldBe "FINALIZED"
                f.source.exists() shouldBe true
                f.command.register(receipt, splitId, "video/mp4", null)
                files.selectBySplittedFileId(splitId).size shouldBe 1
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
        expect("encoded or ambiguous URL paths are rejected before allocating ownership") {
            val f = fixture()
            try {
                listOf("a/%2e%2e/file", "a/%2f/file", "a/%252e/file", "a/file%20name", "a/line\nbreak").forEach { path ->
                    shouldThrow<IllegalArgumentException> {
                        f.command.upload(f.job, "output", f.source, SambaClient.NasType.VIDEO_STORE_NAS, path)
                    }
                    manifests.find(f.job, ProcessingIdentity.hash("output")) shouldBe null
                }
            } finally {
                Thread.interrupted()
                f.source.delete()
            }
        }
    }
}
