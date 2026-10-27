package me.pinfort.tsvideos.catalog.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.catalog.TestLoggerConfig
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.ProgramSearchQuery
import me.pinfort.tsvideos.storage.api.NasComponent
import me.pinfort.tsvideos.storage.api.NasStore
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

/**
 * Catalog operations as Spring wires them (transactional proxies, the one transaction manager and SqlSessionFactory
 * the application provides): MANDATORY locks, participation of locks, reads and coordinated changes in the caller's
 * transaction, and lock retention until the caller's transaction ends. The library callers of the same operations are
 * covered by library's LibraryCatalogTransactionTest.
 */
@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@MybatisTest
@SpringJUnitConfig
@Import(
    LedgerLocks::class,
    LedgerSnapshots::class,
    ProgramInventory::class,
    CreatedFileCatalog::class,
    ProgramCommand::class,
    ExecutedFileCommand::class,
    CreatedFileCommand::class,
    SplittedFileCommand::class,
    ExecutedFileTagCommand::class,
    DirectoryNameComponent::class,
    NormalizeComponent::class,
    TestLoggerConfig::class,
    CatalogTransactionTest.Nas::class,
)
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class CatalogTransactionTest : ExpectSpec() {
    @TestConfiguration
    class Nas {
        @Bean
        fun nasComponent(): NasComponent = mockk()
    }

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Autowired private lateinit var locks: LedgerLocks

    @Autowired private lateinit var snapshots: LedgerSnapshots

    @Autowired private lateinit var inventory: ProgramInventory

    @Autowired private lateinit var programs: ProgramCommand

    @Autowired private lateinit var catalog: CreatedFileCatalog

    @Autowired private lateinit var createdFiles: CreatedFileCommand

    @Autowired private lateinit var nas: NasComponent

    private fun sql(statements: List<String>) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { statement -> statements.forEach { statement.executeUpdate(it) } }
        }
    }

    private fun clear() =
        sql(
            listOf(
                "executed_file_tag",
                "executed_file_check",
                "created_file",
                "splitted_file",
                "program",
                "executed_file",
            ).map { "DELETE FROM $it" },
        )

    // Program 4 on recording 3 with one split and one created file.
    private fun seed() =
        sql(
            listOf(
                "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                    "VALUES(3,'original.m2ts',0,18,'2026-01-01','1','title','channel',1,'SPLITTED')",
                "INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(2,3,'split',3,1,'ENCODE_TASK_ADDED')",
                "INSERT INTO created_file(id,splitted_file_id,file,size,status) VALUES(1,2,'base/a/alpha/file.mp4',3,'FILE_MOVED')",
                "INSERT INTO program(id,name,executed_file_id,status) VALUES(4,'program',3,'COMPLETED')",
            ),
        )

    // Another recording (program 9 on recording 7, split 8) still being processed: its registrations are unrelated to program 4.
    private fun seedOtherRecording() =
        sql(
            listOf(
                "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                    "VALUES(7,'other.m2ts',0,18,'2026-01-02','1','other','channel',1,'SPLITTED')",
                "INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(8,7,'other',3,1,'ENCODE_TASK_ADDED')",
                "INSERT INTO program(id,name,executed_file_id,status) VALUES(9,'other',7,'REGISTERED')",
            ),
        )

    private fun count(
        table: String,
        where: String = "TRUE",
    ): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table WHERE $where").use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    /** Tries to lock program [id] from an independent connection, waiting at most one second. */
    private fun lockFromOtherConnection(id: Long) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    // SET STATEMENT, not SET SESSION: the pooled connection must not keep a 1-second lock wait for later
                    // users (a leaked session value made registrations that should wait fail after 1 second).
                    statement
                        .executeQuery("SET STATEMENT innodb_lock_wait_timeout = 1 FOR SELECT id FROM program WHERE id=$id FOR UPDATE")
                        .close()
                }
            } finally {
                connection.rollback()
            }
        }
    }

    init {
        // Built on use: the transaction manager is injected only after this spec body has run.
        val required by lazy { TransactionTemplate(transactions) }
        val snapshotRead by lazy {
            TransactionTemplate(transactions).apply {
                isReadOnly = true
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            }
        }

        beforeTest {
            clear()
            clearMocks(nas)
            every { nas.deleteResource(any()) } returns NasStore.VIDEO_STORE_NAS
        }
        // Leave the shared container empty so later specs' fixed IDs don't collide.
        afterTest { clear() }

        context("ledger locks") {
            expect("are transactional proxies that refuse to run outside a transaction") {
                seed()
                AopUtils.isAopProxy(locks) shouldBe true
                shouldThrow<IllegalTransactionStateException> { locks.lockCreatedFilePaths(listOf("base/a/alpha/file.mp4"), listOf(2)) }
                shouldThrow<IllegalTransactionStateException> { locks.lockCreatedFileRows(listOf(1)) }
                shouldThrow<IllegalTransactionStateException> { locks.lockCreatedFiles() }
                shouldThrow<IllegalTransactionStateException> { locks.lockWholeLedger() }
                shouldThrow<IllegalTransactionStateException> { locks.lockProgramGraph(4) }
                shouldThrow<IllegalTransactionStateException> { locks.lockProgramRow(4) }
            }

            expect("return the locked rows and stay held until the caller's transaction ends") {
                seed()
                val locked = CountDownLatch(1)
                val release = CountDownLatch(1)
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val holder =
                        executor.submit<Program?> {
                            required.execute {
                                locks.lockCreatedFiles().map { it.id } shouldBe listOf(1L)
                                locks.lockProgramGraph(4).also {
                                    locked.countDown()
                                    check(release.await(30, TimeUnit.SECONDS))
                                }
                            }
                        }
                    check(locked.await(10, TimeUnit.SECONDS))
                    shouldThrow<SQLException> { lockFromOtherConnection(4) }
                    release.countDown()
                    holder.get(10, TimeUnit.SECONDS).shouldNotBeNull().executedFileId shouldBe 3
                    // Released by the commit.
                    lockFromOtherConnection(4)
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                }
            }

            expect("report a missing program without failing the caller's transaction") {
                required.execute {
                    locks.lockProgramGraph(404) shouldBe null
                    locks.lockProgramRow(404) shouldBe false
                }
            }
        }

        context("path guards") {
            val source = "base/a/alpha/file.mp4"
            val destination = "base/b/beta/file.mp4"

            // Registrations of the other recording (split 8), as the pipeline makes them: a transaction of their own.
            fun register(
                path: String,
                split: Long = 8,
            ): CreatedFile = checkNotNull(required.execute { catalog.register(split, path, 1, null, null) })

            expect("hold every alias of the guarded paths and the target's splits while other recordings' writes commit") {
                seed()
                seedOtherRecording()
                val locked = CountDownLatch(1)
                val release = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(10)
                try {
                    // Guard rows already exist for registered paths and splits, so the holder's claims below hit duplicate
                    // keys: those must lock only their own records, not the gaps where other paths' new guards go.
                    required.execute { locks.lockCreatedFilePaths(listOf(source, destination), listOf(2, 8)) }
                    // The per-target locks of an unguarded bulk apply, held as during its NAS phase.
                    val holder =
                        executor.submit {
                            required.execute {
                                locks.lockCreatedFilePaths(listOf(source, destination), listOf(2))
                                locks.lockCreatedFileRows(listOf(1)) shouldBe listOf(1L)
                                locks.lockProgramGraph(4).shouldNotBeNull()
                                snapshots.createdFilesAt(listOf(source, destination)).map { it.id } shouldBe listOf(1L)
                                locked.countDown()
                                check(release.await(60, TimeUnit.SECONDS))
                            }
                        }
                    check(locked.await(10, TimeUnit.SECONDS))

                    // The other recording's registrations (path and split guards, insert, split share lock) and a raw
                    // insert of other paths commit at once, including paths sharing a directory with, or sorting next to,
                    // the guarded ones.
                    val unrelatedPaths = listOf("base/a/alpha/other.mp4", "base/z/zeta/new.mp4", "base/0/0/0.mp4", "base/a/alpha/file.mp5")
                    val unrelated = unrelatedPaths.map { path -> executor.submit<CreatedFile> { register(path) }.get(10, TimeUnit.SECONDS) }
                    val raw = "INSERT INTO created_file(splitted_file_id,file,size,status) VALUES(8,'base/r/raw/new.mp4',1,'FILE_MOVED')"
                    executor.submit { sql(listOf(raw)) }.get(10, TimeUnit.SECONDS)

                    // A registration on the locked program's own split waits even at an unguarded path, on the split guard
                    // and so before it inserts: it would add a file to the target behind the holder's snapshot.
                    val sameTarget = executor.submit<CreatedFile> { register("base/n/new/target.mp4", split = 2) }

                    // Every alias of the source or the destination waits for the holder: registrations and a move.
                    val aliases =
                        listOf(
                            "BASE/A/ALPHA/FILE.MP4",
                            "base\\a\\alpha\\file.mp4",
                            "base/a./alpha /file.mp4. ",
                            "Base/B/Beta/File.mp4",
                            "base\\b\\beta.\\file.mp4 ",
                        )
                    val waiting = aliases.map { path -> executor.submit<CreatedFile> { register(path) } }
                    val moving = executor.submit { required.execute { createdFiles.recordMove(unrelated[0], "BASE\\B\\BETA\\FILE.MP4") } }
                    (waiting + moving + sameTarget).forEach { shouldThrow<TimeoutException> { it.get(300, TimeUnit.MILLISECONDS) } }
                    (waiting + moving + sameTarget).forEach { it.isDone shouldBe false }
                    count("created_file", "splitted_file_id = 2") shouldBe 1

                    release.countDown()
                    holder.get(10, TimeUnit.SECONDS)
                    // Serialized, not refused: once the holder commits they proceed as ordinary writes.
                    waiting.map { it.get(10, TimeUnit.SECONDS).file } shouldBe aliases
                    moving.get(10, TimeUnit.SECONDS)
                    sameTarget.get(10, TimeUnit.SECONDS).splittedFileId shouldBe 2
                    count("created_file") shouldBe 1L + unrelated.size + 1 + aliases.size + 1
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                }
            }

            expect("a registration that waited on a split the holder deletes fails and leaves no row behind") {
                seed()
                val deleted = CountDownLatch(1)
                val release = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val holder =
                        executor.submit {
                            required.execute {
                                locks.lockCreatedFilePaths(listOf(source), listOf(2))
                                locks.lockCreatedFileRows(listOf(1))
                                programs.delete(locks.lockProgramGraph(4).shouldNotBeNull())
                                deleted.countDown()
                                check(release.await(30, TimeUnit.SECONDS))
                            }
                        }
                    check(deleted.await(10, TimeUnit.SECONDS))
                    val late = executor.submit<CreatedFile> { register("base/n/new/late.mp4", split = 2) }
                    shouldThrow<TimeoutException> { late.get(500, TimeUnit.MILLISECONDS) }

                    release.countDown()
                    holder.get(10, TimeUnit.SECONDS)
                    shouldThrow<java.util.concurrent.ExecutionException> { late.get(10, TimeUnit.SECONDS) }
                        .cause
                        .shouldBeInstanceOf<IllegalStateException>()
                    count("splitted_file") shouldBe 0
                    count("created_file") shouldBe 0
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                }
            }

            expect("a registration on a split that does not exist is refused and inserts nothing") {
                seed()
                shouldThrow<IllegalStateException> { register("base/o/orphan/file.mp4", split = 404) }
                count("created_file", "splitted_file_id = 404") shouldBe 0
            }

            expect("a second bulk claim of an alias gives up after the bounded wait") {
                seed()
                val locked = CountDownLatch(1)
                val release = CountDownLatch(1)
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val holder =
                        executor.submit {
                            required.execute {
                                locks.lockCreatedFilePaths(listOf(source), emptyList())
                                locked.countDown()
                                check(release.await(30, TimeUnit.SECONDS))
                            }
                        }
                    check(locked.await(10, TimeUnit.SECONDS))
                    shouldThrow<PessimisticLockingFailureException> {
                        required.execute { locks.lockCreatedFilePaths(listOf("BASE\\A\\ALPHA\\FILE.MP4."), emptyList()) }
                    }
                    // Other paths are not affected.
                    required.execute { locks.lockCreatedFilePaths(listOf(destination), emptyList()) }
                    release.countDown()
                    holder.get(10, TimeUnit.SECONDS)
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                }
            }
        }

        context("coordinated changes") {
            expect("a REQUIRED delete joins the caller's transaction and rolls back with it, NAS first deleted") {
                seed()
                required.execute { status ->
                    locks.lockCreatedFiles()
                    programs.delete(locks.lockProgramGraph(4).shouldNotBeNull())
                    status.setRollbackOnly()
                }
                verify(exactly = 1) { nas.deleteResource("base/a/alpha/file.mp4") }
                count("program") shouldBe 1
                count("created_file") shouldBe 1
                count("splitted_file") shouldBe 1
                count("executed_file") shouldBe 1
            }
        }

        context("reads") {
            expect("join the caller's repeatable-read snapshot") {
                seed()
                snapshotRead.execute {
                    inventory.allIds() shouldBe listOf(4L)
                    sql(
                        listOf(
                            "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                                "VALUES(5,'late.m2ts',0,1,'2026-01-02','1','late','channel',1,'SPLITTED')",
                            "INSERT INTO program(id,name,executed_file_id,status) VALUES(6,'late',5,'COMPLETED')",
                        ),
                    )
                    inventory.allIds() shouldBe listOf(4L)
                    inventory.exists(6) shouldBe false
                    inventory.summaries(listOf(4, 6)).map { it.id } shouldBe listOf(4L)
                    snapshots.programSnapshot(6) shouldBe null
                    programs.search(ProgramSearchQuery()).total shouldBe 1
                }
                inventory.allIds() shouldBe listOf(4L, 6L)
                programs.search(ProgramSearchQuery()).total shouldBe 2
            }
        }
    }
}
