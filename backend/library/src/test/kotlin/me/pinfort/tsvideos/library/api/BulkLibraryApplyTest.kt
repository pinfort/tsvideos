package me.pinfort.tsvideos.library.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.catalog.api.CreatedFileCommand
import me.pinfort.tsvideos.catalog.api.DirectoryNameComponent
import me.pinfort.tsvideos.catalog.api.ExecutedFileCommand
import me.pinfort.tsvideos.catalog.api.ExecutedFileTagCommand
import me.pinfort.tsvideos.catalog.api.LedgerLocks
import me.pinfort.tsvideos.catalog.api.LedgerSnapshots
import me.pinfort.tsvideos.catalog.api.NormalizeComponent
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.api.SplittedFileCommand
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.library.TestLoggerConfig
import me.pinfort.tsvideos.library.domain.BulkAction
import me.pinfort.tsvideos.library.domain.BulkApplyRequest
import me.pinfort.tsvideos.library.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.library.domain.BulkRequest
import me.pinfort.tsvideos.storage.api.NasComponent
import me.pinfort.tsvideos.storage.api.NasStore
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

/**
 * Bulk delete and move over the real catalog operations and database; NAS is a mock. Moved out of catalog's
 * BulkLibraryMapperTest. The move tests hold a NAS move open to check what other created_file writers can do meanwhile.
 */
@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@Import(
    LedgerLocks::class,
    LedgerSnapshots::class,
    CreatedFileCatalog::class,
    ProgramCommand::class,
    ExecutedFileCommand::class,
    CreatedFileCommand::class,
    SplittedFileCommand::class,
    ExecutedFileTagCommand::class,
    DirectoryNameComponent::class,
    NormalizeComponent::class,
    TestLoggerConfig::class,
    BulkLibraryCommand::class,
    BulkLibraryApplyTest.Nas::class,
)
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BulkLibraryApplyTest : ExpectSpec() {
    @TestConfiguration
    class Nas {
        @Bean
        fun nasComponent(): NasComponent = mockk()
    }

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var command: BulkLibraryCommand

    @Autowired private lateinit var snapshots: LedgerSnapshots

    @Autowired private lateinit var programs: ProgramCommand

    @Autowired private lateinit var nas: NasComponent

    @Autowired private lateinit var catalog: CreatedFileCatalog

    @Autowired private lateinit var transactions: PlatformTransactionManager

    private fun sql(statement: String) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.executeUpdate(statement) }
        }
    }

    private fun pathKeyIsNull(id: Long): Boolean =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT path_key IS NULL FROM created_file WHERE id = $id").use {
                    check(it.next())
                    it.getBoolean(1)
                }
            }
        }

    init {
        fun seed() {
            listOf("created_file", "splitted_file", "program", "executed_file").forEach { sql("DELETE FROM $it") }
            sql("INSERT INTO created_file(id,splitted_file_id,file,size,status) VALUES(1,2,'base/a/alpha/file.mp4',3,'FILE_MOVED')")
            sql(
                "INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(2,3,'split',3,1,'ENCODE_TASK_ADDED')",
            )
            sql("INSERT INTO program(id,name,executed_file_id,status) VALUES(4,'program',3,'COMPLETED')")
        }
        beforeTest { clearMocks(nas) }
        expect("actual command rejects added files, isolates failure and preserves local original on deletion") {
            seed()
            val original =
                java.nio.file.Files
                    .createTempFile("bulk-original", ".m2ts")
                    .toFile()
            original.writeText("original recording")
            try {
                val path = original.path.replace("\\", "/").replace("'", "''")
                sql(
                    "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                        "VALUES(3,'$path',0,18,'2026-01-01','1','title','channel',1,'SPLITTED')",
                )
                every { nas.deleteResource(any()) } returns NasStore.VIDEO_STORE_NAS
                val request = BulkRequest(listOf(4), BulkAction.DELETE)
                val initial = command.preview(request)
                sql("INSERT INTO created_file(id,splitted_file_id,file,size,status) VALUES(6,2,'base/a/alpha/second.mp4',2,'FILE_MOVED')")
                command
                    .apply(
                        BulkApplyRequest(request, initial.targets.associate { it.id to it.fingerprint }),
                    ).outcomes
                    .single()
                    .status shouldBe
                    BulkOutcomeStatus.STALE
                val plan = command.preview(request)
                every { nas.deleteResource("base/a/alpha/second.mp4") } throws IllegalStateException("unavailable")
                command
                    .apply(
                        BulkApplyRequest(request, plan.targets.associate { it.id to it.fingerprint }),
                    ).outcomes
                    .single()
                    .status shouldBe
                    BulkOutcomeStatus.FAILED
                snapshots.allCreatedFiles().size shouldBe 2
                programs.find(4)?.id shouldBe 4
                original.readText() shouldBe "original recording"
                every { nas.deleteResource(any()) } returns NasStore.VIDEO_STORE_NAS
                command
                    .apply(
                        BulkApplyRequest(request, plan.targets.associate { it.id to it.fingerprint }),
                    ).outcomes
                    .single()
                    .status shouldBe
                    BulkOutcomeStatus.SUCCESS
                programs.find(4) shouldBe null
                snapshots.allCreatedFiles().size shouldBe 0
                original.readText() shouldBe "original recording"
            } finally {
                original.delete()
            }
        }

        context("move apply and concurrent created_file writes (#112)") {
            val source = "base/a/alpha/file.mp4"
            val destination = "base\\b\\beta\\file.mp4"
            val move = BulkRequest(listOf(4), BulkAction.MOVE, "beta")

            // Registers the way the pipeline does, in a transaction of its own: by default on split 99 of another
            // recording still being processed (program 9), or on program 4's own split 2.
            fun register(
                path: String,
                split: Long = 99,
            ): CreatedFile = checkNotNull(TransactionTemplate(transactions).execute { catalog.register(split, path, 1, null, null) })

            fun seedRecording() {
                seed()
                sql(
                    "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                        "VALUES(3,'original.m2ts',0,18,'2026-01-01','1','title','channel',1,'SPLITTED')",
                )
                sql(
                    "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                        "VALUES(7,'other.m2ts',0,18,'2026-01-02','1','other','channel',1,'SPLITTED')",
                )
                sql("INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(99,7,'other',3,1,'ENCODE_TASK_ADDED')")
                sql("INSERT INTO program(id,name,executed_file_id,status) VALUES(9,'other',7,'REGISTERED')")
            }

            fun filesOfSplit(split: Long) = snapshots.allCreatedFiles().filter { it.splittedFileId == split }

            expect("during the NAS phase other paths are registered while every source and destination alias waits") {
                seedRecording()
                val inNas = CountDownLatch(1)
                val release = CountDownLatch(1)
                every { nas.moveResource(source, destination) } answers {
                    inNas.countDown()
                    check(release.await(60, TimeUnit.SECONDS))
                    NasStore.VIDEO_STORE_NAS
                }
                val executor = Executors.newFixedThreadPool(8)
                try {
                    val plan = command.preview(move)
                    plan.targets.single().eligible shouldBe true
                    val applying =
                        executor.submit<BulkOutcomeStatus> {
                            command
                                .apply(BulkApplyRequest(move, plan.targets.associate { it.id to it.fingerprint }))
                                .outcomes
                                .single()
                                .status
                        }
                    check(inNas.await(30, TimeUnit.SECONDS))

                    // Unrelated registrations and a raw insert commit while the apply holds its locks during NAS I/O.
                    val unrelated = listOf("base/a/alpha/other.mp4", "base/B/beta/other.mp4", "base/z/zeta/file.mp4")
                    unrelated.forEach { path -> executor.submit<CreatedFile> { register(path) }.get(10, TimeUnit.SECONDS) }
                    val raw = "INSERT INTO created_file(splitted_file_id,file,size,status) VALUES(99,'base/r/raw/file.mp4',1,'FILE_MOVED')"
                    executor.submit { sql(raw) }.get(10, TimeUnit.SECONDS)

                    // Aliases of the source and of the destination (case, separators, trailing dots and spaces) wait.
                    val aliases =
                        listOf(
                            "BASE/A/ALPHA/FILE.MP4",
                            "base\\a\\alpha\\file.mp4",
                            "base/a./alpha /file.mp4. ",
                            "base/b/beta/file.mp4",
                            "BASE\\B\\BETA\\FILE.MP4 .",
                        )
                    val waiting = aliases.map { path -> executor.submit<CreatedFile> { register(path) } }
                    // A file registered on the target's own split at any path would be missed by the move: it waits too.
                    val sameTarget = executor.submit<CreatedFile> { register("base/a/alpha/late.mp4", split = 2) }
                    (waiting + sameTarget).forEach { shouldThrow<TimeoutException> { it.get(300, TimeUnit.MILLISECONDS) } }
                    applying.isDone shouldBe false
                    filesOfSplit(2).map { it.file } shouldBe listOf(source)

                    release.countDown()
                    applying.get(30, TimeUnit.SECONDS) shouldBe BulkOutcomeStatus.SUCCESS
                    // Serialized behind the apply, then ordinary writes once it has committed.
                    waiting.map { it.get(10, TimeUnit.SECONDS).file } shouldBe aliases
                    sameTarget.get(10, TimeUnit.SECONDS).splittedFileId shouldBe 2
                    // The moved file plus the registration that came after the move, never a file the move skipped.
                    snapshots.programSnapshot(4)?.createdFiles?.map { it.file } shouldBe listOf(destination, "base/a/alpha/late.mp4")
                    snapshots.allCreatedFiles().size shouldBe 1 + unrelated.size + 1 + aliases.size + 1
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                }
            }

            expect("a registration on the target's split during a delete waits, then fails without leaving an orphan row") {
                seedRecording()
                val inNas = CountDownLatch(1)
                val release = CountDownLatch(1)
                every { nas.deleteResource(source) } answers {
                    inNas.countDown()
                    check(release.await(60, TimeUnit.SECONDS))
                    NasStore.VIDEO_STORE_NAS
                }
                val delete = BulkRequest(listOf(4), BulkAction.DELETE)
                val executor = Executors.newFixedThreadPool(4)
                try {
                    val plan = command.preview(delete)
                    val applying =
                        executor.submit<BulkOutcomeStatus> {
                            command
                                .apply(BulkApplyRequest(delete, plan.targets.associate { it.id to it.fingerprint }))
                                .outcomes
                                .single()
                                .status
                        }
                    check(inNas.await(30, TimeUnit.SECONDS))
                    val late = executor.submit<CreatedFile> { register("base/a/alpha/late.mp4", split = 2) }
                    // The other recording keeps registering meanwhile.
                    executor.submit<CreatedFile> { register("base/z/zeta/file.mp4") }.get(10, TimeUnit.SECONDS)
                    shouldThrow<TimeoutException> { late.get(500, TimeUnit.MILLISECONDS) }

                    release.countDown()
                    applying.get(30, TimeUnit.SECONDS) shouldBe BulkOutcomeStatus.SUCCESS
                    shouldThrow<ExecutionException> { late.get(10, TimeUnit.SECONDS) }.cause.shouldBeInstanceOf<IllegalStateException>()
                    programs.find(4) shouldBe null
                    filesOfSplit(2) shouldBe emptyList()
                    snapshots.allCreatedFiles().map { it.file } shouldBe listOf("base/z/zeta/file.mp4")
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                }
            }

            expect("a direct move of a program a bulk move holds waits before any NAS change, then refuses the changed files") {
                seedRecording()
                val inNas = CountDownLatch(1)
                val release = CountDownLatch(1)
                every { nas.moveResource(source, destination) } answers {
                    inNas.countDown()
                    check(release.await(60, TimeUnit.SECONDS))
                    NasStore.VIDEO_STORE_NAS
                }
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val plan = command.preview(move)
                    val applying =
                        executor.submit<BulkOutcomeStatus> {
                            command
                                .apply(BulkApplyRequest(move, plan.targets.associate { it.id to it.fingerprint }))
                                .outcomes
                                .single()
                                .status
                        }
                    check(inNas.await(30, TimeUnit.SECONDS))
                    // The manager console's Modify: plans from the committed rows, then waits on the source's guard.
                    val direct = executor.submit { programs.moveCreatedFiles(checkNotNull(programs.find(4)), "gamma") }
                    shouldThrow<TimeoutException> { direct.get(500, TimeUnit.MILLISECONDS) }

                    release.countDown()
                    applying.get(30, TimeUnit.SECONDS) shouldBe BulkOutcomeStatus.SUCCESS
                    shouldThrow<ExecutionException> { direct.get(30, TimeUnit.SECONDS) }.cause.shouldBeInstanceOf<IllegalStateException>()
                    // Only the bulk move reached the NAS (an unstubbed move by the direct caller would also fail it).
                    verify(exactly = 1) { nas.moveResource(any(), any()) }
                    snapshots.programSnapshot(4)?.createdFiles?.map { it.file } shouldBe listOf(destination)
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                }
            }

            expect("a direct move holds its source, destination and splits during NAS I/O while other recordings register") {
                seedRecording()
                val inNas = CountDownLatch(1)
                val release = CountDownLatch(1)
                every { nas.moveResource(source, destination) } answers {
                    inNas.countDown()
                    check(release.await(60, TimeUnit.SECONDS))
                    NasStore.VIDEO_STORE_NAS
                }
                val executor = Executors.newFixedThreadPool(6)
                try {
                    val direct = executor.submit { programs.moveCreatedFiles(checkNotNull(programs.find(4)), "beta") }
                    check(inNas.await(30, TimeUnit.SECONDS))

                    executor.submit<CreatedFile> { register("base/z/zeta/file.mp4") }.get(10, TimeUnit.SECONDS)
                    val blocked =
                        listOf(
                            executor.submit<CreatedFile> { register("BASE/B/BETA/FILE.MP4") },
                            executor.submit<CreatedFile> { register("Base\\A\\Alpha\\File.mp4.") },
                            executor.submit<CreatedFile> { register("base/a/alpha/late.mp4", split = 2) },
                        )
                    blocked.forEach { shouldThrow<TimeoutException> { it.get(300, TimeUnit.MILLISECONDS) } }
                    filesOfSplit(2).map { it.file } shouldBe listOf(source)
                    // A bulk apply of the same program gives up after its bounded wait without changing anything.
                    val plan = command.preview(BulkRequest(listOf(4), BulkAction.DELETE))
                    command
                        .apply(BulkApplyRequest(plan.request, plan.targets.associate { it.id to it.fingerprint }))
                        .outcomes
                        .single()
                        .status shouldBe BulkOutcomeStatus.FAILED
                    verify(exactly = 0) { nas.deleteResource(any()) }

                    release.countDown()
                    direct.get(30, TimeUnit.SECONDS)
                    blocked.map { it.get(10, TimeUnit.SECONDS).file } shouldBe
                        listOf("BASE/B/BETA/FILE.MP4", "Base\\A\\Alpha\\File.mp4.", "base/a/alpha/late.mp4")
                    snapshots.programSnapshot(4)?.createdFiles?.map { it.file } shouldBe listOf(destination, "base/a/alpha/late.mp4")
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                }
            }

            expect("an alias of the destination registered after preview makes apply stale before any NAS change") {
                seedRecording()
                val plan = command.preview(move)
                plan.targets.single().eligible shouldBe true
                register("BASE/b/BETA/file.mp4.")
                command
                    .apply(BulkApplyRequest(move, plan.targets.associate { it.id to it.fingerprint }))
                    .outcomes
                    .single()
                    .status shouldBe BulkOutcomeStatus.STALE
                verify(exactly = 0) { nas.moveResource(any(), any()) }
                snapshots.programSnapshot(4)?.createdFiles?.map { it.file } shouldBe listOf(source)
            }

            expect("an unkeyed alias of the destination that the backfill cannot lock still refuses the move without NAS changes") {
                seedRecording()
                val plan = command.preview(move)
                plan.targets.single().eligible shouldBe true
                // Written by SQL outside catalog (or before migration 076): no path_key, at an alias of the destination.
                sql("INSERT INTO created_file(id,splitted_file_id,file,size,status) VALUES(50,99,'base/b/beta/FILE.MP4.',1,'FILE_MOVED')")
                val refused = "Destination is already referenced or duplicated"
                dataSource.connection.use { holder ->
                    holder.autoCommit = false
                    try {
                        // Another transaction holds the row, so the backfill (FOR UPDATE SKIP LOCKED) passes over it.
                        holder.createStatement().use { it.executeQuery("SELECT id FROM created_file WHERE id = 50 FOR UPDATE").close() }
                        val again = command.preview(move)
                        pathKeyIsNull(50) shouldBe true
                        again.targets.single().eligible shouldBe false
                        again.targets.single().reason shouldBe refused

                        // The earlier plan is stale; the current one is refused for the alias, not for a busy lock.
                        val stale = command.apply(BulkApplyRequest(move, plan.targets.associate { it.id to it.fingerprint }))
                        stale.outcomes.single().status shouldBe BulkOutcomeStatus.STALE
                        val failed = command.apply(BulkApplyRequest(move, again.targets.associate { it.id to it.fingerprint }))
                        failed.outcomes.single().status shouldBe BulkOutcomeStatus.FAILED
                        failed.outcomes.single().message shouldBe refused
                        pathKeyIsNull(50) shouldBe true
                    } finally {
                        holder.rollback()
                    }
                }
                verify(exactly = 0) { nas.moveResource(any(), any()) }
                snapshots.programSnapshot(4)?.createdFiles?.map { it.file } shouldBe listOf(source)
                // Once released, the next preview stores the key and still refuses through the index.
                command
                    .preview(move)
                    .targets
                    .single()
                    .reason shouldBe refused
                pathKeyIsNull(50) shouldBe false
            }

            expect("an alias of the source registered after preview makes a delete stale") {
                seedRecording()
                val delete = BulkRequest(listOf(4), BulkAction.DELETE)
                val plan = command.preview(delete)
                register("Base\\A\\Alpha\\File.mp4")
                command
                    .apply(BulkApplyRequest(delete, plan.targets.associate { it.id to it.fingerprint }))
                    .outcomes
                    .single()
                    .status shouldBe BulkOutcomeStatus.STALE
                verify(exactly = 0) { nas.deleteResource(any()) }
            }
        }
    }
}
