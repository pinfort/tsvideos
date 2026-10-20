package me.pinfort.tsvideos.core.catalog

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.command.BulkLibraryCommand
import me.pinfort.tsvideos.core.command.CreatedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileTagCommand
import me.pinfort.tsvideos.core.command.LibraryProgramSearchCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.component.DirectoryNameComponent
import me.pinfort.tsvideos.core.component.NormalizeComponent
import me.pinfort.tsvideos.core.config.LoggerConfig
import me.pinfort.tsvideos.core.domain.BulkAction
import me.pinfort.tsvideos.core.domain.BulkApplyRequest
import me.pinfort.tsvideos.core.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.core.domain.BulkRequest
import me.pinfort.tsvideos.core.domain.LibraryProgramFilter
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramSearchQuery
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Catalog operations as Spring wires them (transactional proxies, one shared transaction manager): MANDATORY locks,
 * participation of locks, reads and coordinated changes in the caller's transaction, lock retention until the
 * caller's transaction ends, and library search compatibility with the catalog search.
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
    LoggerConfig::class,
    BulkLibraryCommand::class,
    LibraryProgramSearchCommand::class,
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

    @Autowired private lateinit var bulk: BulkLibraryCommand

    @Autowired private lateinit var librarySearch: LibraryProgramSearchCommand

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
                "library_collection",
                "library_tag",
                "executed_file_tag",
                "executed_file_check",
                "created_file",
                "splitted_file",
                "program",
                "executed_file",
            ).map { "DELETE FROM $it" },
        )

    // Program 4 on recording 3 with one split and one created file, all eligible for a bulk change.
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

    private fun count(table: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use {
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
                    statement.execute("SET SESSION innodb_lock_wait_timeout = 1")
                    statement.executeQuery("SELECT id FROM program WHERE id=$id FOR UPDATE").close()
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

            expect("bulk apply runs the MANDATORY locks inside its per-target transaction and commits the change") {
                seed()
                val request = BulkRequest(listOf(4), BulkAction.DELETE)
                val plan = bulk.preview(request)
                val result = bulk.apply(BulkApplyRequest(request, plan.targets.associate { it.id to it.fingerprint }))
                result.outcomes.single().status shouldBe BulkOutcomeStatus.SUCCESS
                count("program") shouldBe 0
                count("created_file") shouldBe 0
            }

            expect("a NAS failure during bulk apply rolls the target's rows back") {
                seed()
                every { nas.deleteResource(any()) } throws IllegalStateException("unavailable")
                val request = BulkRequest(listOf(4), BulkAction.DELETE)
                val plan = bulk.preview(request)
                val result = bulk.apply(BulkApplyRequest(request, plan.targets.associate { it.id to it.fingerprint }))
                result.outcomes.single().status shouldBe BulkOutcomeStatus.FAILED
                count("program") shouldBe 1
                count("created_file") shouldBe 1
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
                    librarySearch.search(ProgramSearchQuery()).total shouldBe 1
                }
                inventory.allIds() shouldBe listOf(4L, 6L)
            }
        }

        context("library search") {
            fun seedSearch() {
                sql(
                    listOf(
                        """
                        INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) VALUES
                        (1,'one',0,100,'2026-10-01 00:00:00','BS1','t','channel',60,'SPLITTED'),
                        (2,'two',5,200,'2026-10-02 00:00:00','BS1','t','channel',30,'SPLITTED'),
                        (3,'three',-1,300,'2026-10-02 00:00:00','BS2','t','channel',90,'SPLITTED'),
                        (4,'four',2,200,'2026-10-03 00:00:00','BS1','t','channel',30,'SPLITTED'),
                        (5,'five',9,500,'2026-10-04 00:00:00','BS1','t','channel',120,'SPLITTED')
                        """.trimIndent(),
                        """
                        INSERT INTO program(id,name,executed_file_id,status) VALUES
                        (1,'News One',1,'COMPLETED'),(2,'NEWS Two',2,'COMPLETED'),(3,'Drama',3,'ERROR'),
                        (4,'News Four',4,'REGISTERED'),(5,'100%_News',5,'COMPLETED'),(6,'Orphan',99,'ERROR')
                        """.trimIndent(),
                        """
                        INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES
                        (1,1,'split1',100,60,'ENCODE_TASK_ADDED'),(2,2,'split2',200,30,'ENCODE_TASK_ADDED'),
                        (4,4,'split4',200,30,'ENCODE_TASK_ADDED')
                        """.trimIndent(),
                        """
                        INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES
                        (1,1,'ts1',100,'video/vnd.dlna.mpeg-tts','gzip','FILE_MOVED'),
                        (2,2,'mp4a',20,'video/mp4',NULL,'FILE_MOVED'),
                        (3,4,'subtitle',30,'text/plain',NULL,'FILE_MOVED')
                        """.trimIndent(),
                        "INSERT INTO library_collection(id,name,name_key) VALUES(900,'all','all')",
                        "INSERT INTO library_collection_program(label_id,program_id) SELECT 900, id FROM program",
                        "INSERT INTO library_tag(id,name,name_key) VALUES(901,'pick','pick')",
                        "INSERT INTO library_tag_program(label_id,program_id) VALUES(901,2),(901,4),(901,6)",
                    ),
                )
            }

            val queries =
                ProgramSearchQuery.Sort.entries.flatMap { sort ->
                    ProgramSearchQuery.Direction.entries.map { direction -> ProgramSearchQuery(sort = sort, direction = direction) }
                } +
                    listOf(
                        ProgramSearchQuery(name = "news"),
                        ProgramSearchQuery(name = "%_"),
                        ProgramSearchQuery(channel = "BS1", status = Program.Status.COMPLETED),
                        ProgramSearchQuery(recordedFrom = LocalDateTime.parse("2026-10-02T00:00:00")),
                        ProgramSearchQuery(recordedTo = LocalDateTime.parse("2026-10-03T00:00:00")),
                        ProgramSearchQuery(minDrops = 0, maxDrops = 5),
                        ProgramSearchQuery(fileType = ProgramSearchQuery.FileType.TS),
                        ProgramSearchQuery(fileType = ProgramSearchQuery.FileType.ENCODED),
                    )

            expect("with a collection holding every program equals the catalog search, including order, total and paging") {
                seedSearch()
                for (query in queries) {
                    for (page in listOf(query, query.copy(limit = 2, offset = 1))) {
                        librarySearch.search(page, LibraryProgramFilter(collectionId = 900)) shouldBe programs.search(page)
                    }
                }
            }

            expect("narrows the catalog result to tagged programs and keeps its order and paging") {
                seedSearch()
                val tagged = setOf(2L, 4L, 6L)
                for (query in queries) {
                    val full = programs.search(query.copy(limit = 500, offset = 0)).items.filter { it.id in tagged }
                    val filter = LibraryProgramFilter(collectionId = 900, libraryTagId = 901)
                    librarySearch.search(query, filter).items shouldBe full
                    librarySearch.search(query, filter).total shouldBe full.size.toLong()
                    librarySearch.search(query.copy(limit = 1, offset = 1), filter).items shouldBe full.drop(1).take(1)
                }
                librarySearch.search(ProgramSearchQuery(), LibraryProgramFilter(libraryTagId = 999)).total shouldBe 0
            }
        }
    }
}
