package me.pinfort.tsvideos.core.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.UploadManifest.State
import me.pinfort.tsvideos.core.domain.sha256Hex
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
import java.nio.file.Files
import java.util.UUID

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@MybatisTest
@org.springframework.context.annotation.Import(VerifiedUploadSpringConfiguration::class)
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class VerifiedUploadCommandTest : ExpectSpec() {
    @Autowired private lateinit var manifests: UploadManifestMapper

    @Autowired private lateinit var files: CreatedFileMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Autowired private lateinit var springCommand: VerifiedUploadCommand

    @Autowired private lateinit var springNas: VerifiedUploadNas

    @Autowired private lateinit var dataSource: javax.sql.DataSource

    init {
        data class Fixture(
            val command: VerifiedUploadCommand,
            val nas: VerifiedUploadNas,
            val source: File,
            val remote: MutableMap<String, ByteArray>,
            val job: Long,
            val target: String,
        )

        fun ByteArray.sha256() = inputStream().sha256Hex()

        fun fixture(
            checksum: Boolean = true,
            spring: Boolean = false,
            name: String = "video.mp4",
        ): Fixture {
            val nas = if (spring) springNas else mockk<VerifiedUploadNas>()
            val remote = mutableMapOf<String, ByteArray>()
            every { nas.exists(any(), any()) } answers { remote.containsKey(secondArg()) }
            every { nas.size(any(), any()) } answers { remote.getValue(secondArg()).size.toLong() }
            every { nas.sha256(any(), any()) } answers {
                remote.getValue(secondArg()).sha256()
            }
            every { nas.transfer(any(), any(), any(), any(), any()) } answers {
                val path = secondArg<String>()
                check(!remote.containsKey(path))
                arg<() -> Unit>(3)()
                arg<File>(2).readBytes().also { remote[path] = it }.sha256()
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
                if (spring) springCommand else VerifiedUploadCommand(manifests, nas, created, files, transactions, checksum),
                nas,
                source,
                remote,
                job,
                "test/${UUID.randomUUID()}/$name",
            )
        }

        fun Fixture.upload() = command.upload(job, "output", source, SambaClient.NasType.VIDEO_STORE_NAS, target)

        fun Fixture.manifest() = checkNotNull(manifests.find(job, ProcessingIdentity.hash("output")))

        expect("Spring upload commits ownership before real SMB effects") {
            val container =
                org.testcontainers.containers
                    .GenericContainer("ghcr.io/servercontainers/samba:latest")
                    .withExposedPorts(445)
                    .withEnv("AVAHI_DISABLE", "1")
                    .withEnv("WSDD2_DISABLE", "1")
                    .withEnv("NETBIOS_DISABLE", "1")
                    .withEnv("ACCOUNT_uploadtest", "uploadtest")
                    .withEnv("UID_uploadtest", "1000")
                    .withEnv(
                        "SAMBA_VOLUME_CONFIG_upload",
                        "[upload]; path=/shares; valid users = uploadtest; guest ok = no; read only = no",
                    ).withTmpFs(mapOf("/shares" to "rw"))
                    .waitingFor(
                        org.testcontainers.containers.wait.strategy.Wait
                            .forListeningPort(),
                    )
            container.start()
            // '#' and '%' are common in titles and must reach SMB literally.
            val f = fixture(spring = true, name = "アニメ #3 100%.mp4")
            try {
                container.execInContainer("sh", "-c", "chown 1000:1000 /shares && chmod 770 /shares").exitCode shouldBe 0
                val server =
                    me.pinfort.tsvideos.core.config.SambaConfigurationProperties.Server(
                        "smb://${container.host}:${container.getMappedPort(445)}/upload",
                        "uploadtest",
                        "uploadtest",
                        "",
                    )
                val samba =
                    SambaClient(
                        me.pinfort.tsvideos.core.config
                            .SambaConfigurationProperties(server, server),
                    )
                val real =
                    VerifiedUploadNas(
                        samba,
                        me.pinfort.tsvideos.core.external.samba
                            .NasComponent(samba, mockk(relaxed = true)),
                    )

                fun committed(
                    state: String,
                    claimed: Boolean,
                ) {
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive() shouldBe false
                    dataSource.connection.use { connection ->
                        connection.autoCommit = true
                        connection.prepareStatement("SELECT state,claim_token FROM upload_manifest WHERE job_id=?").use { statement ->
                            statement.setLong(1, f.job)
                            statement.executeQuery().use { rows ->
                                rows.next() shouldBe true
                                rows.getString(1) shouldBe state
                                (rows.getString(2) != null) shouldBe claimed
                            }
                        }
                    }
                }
                every { springNas.exists(any(), any()) } answers { real.exists(firstArg(), secondArg()) }
                every { springNas.size(any(), any()) } answers { real.size(firstArg(), secondArg()) }
                every { springNas.sha256(any(), any()) } answers { real.sha256(firstArg(), secondArg()) }
                every { springNas.transfer(any(), any(), any(), any(), any()) } answers {
                    committed("CREATING", true)
                    real.transfer(firstArg(), secondArg(), thirdArg(), {
                        arg<() -> Unit>(3)()
                        committed("TRANSFERRING", true)
                    }, arg(4))
                }
                every { springNas.finalize(any(), any(), any()) } answers {
                    committed("FINALIZING", true)
                    real.finalize(firstArg(), secondArg(), thirdArg())
                }
                val receipt = f.upload()
                committed("FINALIZED", false)
                f.command.register(receipt, 876543, "video/mp4", null)
                committed("REGISTERED", false)
                f.command.deleteLocal(receipt)
                f.source.exists() shouldBe false
                f.upload() shouldBe receipt
                real.exists(SambaClient.NasType.VIDEO_STORE_NAS, f.target) shouldBe true
            } finally {
                f.source.delete()
                container.stop()
            }
        }
        expect("Spring transactional upload observes committed manifest insert and registration") {
            org.springframework.aop.support.AopUtils
                .isAopProxy(springCommand) shouldBe true
            val f = fixture(spring = true)
            try {
                val receipt = f.upload()
                f.manifest().state shouldBe State.FINALIZED
                val registered = f.command.register(receipt, 876543, "video/mp4", null)
                manifests.get(receipt.manifestId)?.createdFileId shouldBe registered.id
                f.command.deleteLocal(receipt)
                f.source.exists() shouldBe false
                f.upload() shouldBe receipt
                f.command.register(receipt, 876543, "video/mp4", null).id shouldBe registered.id
                f.remote.keys shouldBe setOf(f.target)
            } finally {
                f.source.delete()
            }
        }
        expect("successful upload is registered exactly once and repeated callback survives local deletion") {
            val f = fixture()
            try {
                val receipt = f.upload()
                f.source.exists() shouldBe true
                f.manifest().state shouldBe State.FINALIZED
                val first = f.command.register(receipt, 876543, "video/mp4", null)
                f.command.register(receipt, 876543, "video/mp4", null).id shouldBe first.id
                f.command.deleteLocal(receipt)
                f.source.exists() shouldBe false
                f.upload() shouldBe receipt
                f.command.register(receipt, 876543, "video/mp4", null).id shouldBe first.id
                f.remote.keys shouldBe setOf(f.target)
            } finally {
                f.source.delete()
            }
        }
        expect("truncated transfer preserves local source and retry replaces only owned temporary artifact") {
            val f = fixture()
            every { f.nas.transfer(any(), any(), any(), any(), any()) } answers {
                arg<() -> Unit>(3)()
                byteArrayOf(1).also { f.remote[secondArg()] = it }.sha256()
            }
            try {
                shouldThrow<IllegalStateException> { f.upload() }
                f.manifest().state shouldBe State.TRANSFERRING
                f.source.exists() shouldBe true
                f.remote.containsKey(f.target) shouldBe false
                every { f.nas.transfer(any(), any(), any(), any(), any()) } answers {
                    arg<() -> Unit>(3)()
                    f.source
                        .readBytes()
                        .also { f.remote[secondArg()] = it }
                        .sha256()
                }
                f.upload()
                f.manifest().state shouldBe State.FINALIZED
            } finally {
                f.source.delete()
            }
        }
        expect("same sized unrelated final file never grants ownership") {
            val f = fixture()
            try {
                f.remote[f.target] = f.source.readBytes()
                shouldThrow<IllegalStateException> { f.upload() }
                f.manifest().state shouldBe State.NEW
                f.remote.keys shouldBe setOf(f.target)
                f.source.exists() shouldBe true
            } finally {
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
                    f.manifest().state shouldBe State.TRANSFERRING
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
                f.manifest().state shouldBe State.FINALIZING
                shouldThrow<IllegalStateException> { f.upload() }
                f.source.exists() shouldBe true
            } finally {
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
                f.manifest().state shouldBe State.FINALIZED
                shouldThrow<IllegalStateException> { command.deleteLocal(receipt) }
                f.source.exists() shouldBe true
                f.command.register(receipt, 1, "video/mp4", null)
                f.command.deleteLocal(receipt)
                f.source.exists() shouldBe false
            } finally {
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
                f.source.delete()
            }
        }
        expect("same size and mtime content swap is caught by the full hash before deletion") {
            val f = fixture()
            try {
                val receipt = f.upload()
                f.command.register(receipt, 1, "video/mp4", null)
                val modified = f.source.lastModified()
                f.source.writeText("COMPLETE VIDEO")
                f.source.setLastModified(modified)
                // Repeated callbacks only re-check the fingerprint, so they still succeed...
                f.upload() shouldBe receipt
                // ...but the content is re-hashed before anything local is deleted.
                shouldThrow<IllegalStateException> { f.command.deleteLocal(receipt) }
                f.source.exists() shouldBe true
            } finally {
                f.source.delete()
            }
        }
        expect("default size-only policy never reads the NAS copy back; checksum policy reads each copy once") {
            listOf(false to 0, true to 2).forEach { (checksum, readbacks) ->
                val f = fixture(checksum = checksum)
                try {
                    f.upload()
                    verify(exactly = readbacks) { f.nas.sha256(any(), any()) }
                } finally {
                    f.source.delete()
                }
            }
        }
        expect("a destination owned by another job names the owner") {
            val owner = fixture()
            val other = fixture()
            try {
                val receipt = owner.upload()
                val error =
                    shouldThrow<IllegalStateException> {
                        other.command.upload(other.job, "output", other.source, SambaClient.NasType.VIDEO_STORE_NAS, owner.target)
                    }
                error.message shouldBe
                    "Destination ${owner.target} belongs to upload ${receipt.manifestId} of job ${owner.job}; reconciliation required"
            } finally {
                owner.source.delete()
                other.source.delete()
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
                f.manifest().state shouldBe State.TRANSFERRING
                f.source.exists() shouldBe true
            } finally {
                f.source.delete()
            }
        }
        expect("failure before any temporary file exists returns CREATING to NEW on retry") {
            val f = fixture()
            val owning = f.nas
            every { owning.transfer(any(), any(), any(), any(), any()) } throws IllegalStateException("share unreachable")
            try {
                shouldThrow<IllegalStateException> { f.upload() }
                f.manifest().state shouldBe State.CREATING
                every { owning.transfer(any(), any(), any(), any(), any()) } answers {
                    arg<() -> Unit>(3)()
                    f.source
                        .readBytes()
                        .also { f.remote[secondArg()] = it }
                        .sha256()
                }
                f.upload()
                f.manifest().state shouldBe State.FINALIZED
                f.remote.keys shouldBe setOf(f.target)
            } finally {
                f.source.delete()
            }
        }
        expect("uncertain exclusive open that left a temporary file is never deleted as owned") {
            val f = fixture()
            every { f.nas.transfer(any(), any(), any(), any(), any()) } answers {
                f.remote[secondArg()] = byteArrayOf()
                error("open outcome unknown")
            }
            try {
                shouldThrow<IllegalStateException> { f.upload() }
                f.manifest().state shouldBe State.CREATING
                shouldThrow<IllegalStateException> { f.command.retireAfterReset(f.job) }
                shouldThrow<IllegalStateException> { f.upload() }
                f.manifest().state shouldBe State.CREATING
                f.remote.keys shouldBe setOf(f.manifest().temporaryPath)
                f.source.exists() shouldBe true
            } finally {
                f.source.delete()
            }
        }
        expect("claim release failure does not mask the original failure") {
            val f = fixture()
            try {
                val receipt = f.upload()
                val failing = mockk<UploadManifestMapper>()
                every { failing.claim(any(), any()) } answers { manifests.claim(firstArg(), secondArg()) }
                every { failing.get(any()) } answers { manifests.get(firstArg()) }
                every { failing.release(any(), any()) } throws IllegalStateException("release failed")
                val created = CreatedFileCommand(files, mockk(), mockk(), mockk(relaxed = true))
                val command = VerifiedUploadCommand(failing, f.nas, created, files, transactions)
                val error = shouldThrow<IllegalStateException> { command.deleteLocal(receipt) }
                error.message shouldBe "Remote artifact has not been registered"
                error.suppressed.map { it.message } shouldBe listOf("release failed")
                f.source.exists() shouldBe true
            } finally {
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
                f.source
                    .readBytes()
                    .also { f.remote[secondArg()] = it }
                    .sha256()
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
                f.manifest().state shouldBe State.FINALIZED
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
                manifests.get(receipt.manifestId)?.state shouldBe State.REGISTERED
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
                f.manifest().state shouldBe State.NEW
                f.remote.keys shouldBe setOf("unrelated.upload-file")
                f.source.exists() shouldBe true
            } finally {
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
                f.manifest().state shouldBe State.FINALIZED
                f.source.exists() shouldBe true
                f.command.register(receipt, splitId, "video/mp4", null)
                files.selectBySplittedFileId(splitId).size shouldBe 1
            } finally {
                f.source.delete()
            }
        }
        expect("traversal, stream, wildcard and control-character paths are rejected before allocating ownership") {
            val f = fixture()
            try {
                listOf("/abs/file", "a\\b", "a//b", "a/../b", "a/./b", "a/b:stream", "a/what?", "a/line\nbreak").forEach { path ->
                    shouldThrow<IllegalArgumentException> {
                        f.command.upload(f.job, "output", f.source, SambaClient.NasType.VIDEO_STORE_NAS, path)
                    }.message shouldStartWith "NAS target"
                    manifests.find(f.job, ProcessingIdentity.hash("output")) shouldBe null
                }
            } finally {
                f.source.delete()
            }
        }
        expect("title characters such as '#' and '%' are stored literally") {
            val f = fixture(name = "アニメ #3 100% %2e.mp4")
            try {
                f.upload()
                f.manifest().finalPath shouldBe f.target
                f.remote.keys shouldBe setOf(f.target)
            } finally {
                f.source.delete()
            }
        }
    }
}

@org.springframework.boot.test.context.TestConfiguration
class VerifiedUploadSpringConfiguration {
    @org.springframework.context.annotation.Bean
    fun uploadNas(): VerifiedUploadNas = mockk()

    @org.springframework.context.annotation.Bean
    fun uploadCommand(
        manifests: UploadManifestMapper,
        nas: VerifiedUploadNas,
        files: CreatedFileMapper,
        transactions: PlatformTransactionManager,
    ): VerifiedUploadCommand =
        VerifiedUploadCommand(
            manifests,
            nas,
            CreatedFileCommand(files, mockk(), mockk(), mockk(relaxed = true)),
            files,
            transactions,
            true,
        )
}
