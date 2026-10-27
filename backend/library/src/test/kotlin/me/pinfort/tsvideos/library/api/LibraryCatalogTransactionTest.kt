package me.pinfort.tsvideos.library.api

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import me.pinfort.tsvideos.catalog.api.CreatedFileCommand
import me.pinfort.tsvideos.catalog.api.DirectoryNameComponent
import me.pinfort.tsvideos.catalog.api.ExecutedFileCommand
import me.pinfort.tsvideos.catalog.api.ExecutedFileTagCommand
import me.pinfort.tsvideos.catalog.api.LedgerLocks
import me.pinfort.tsvideos.catalog.api.LedgerSnapshots
import me.pinfort.tsvideos.catalog.api.NormalizeComponent
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.api.ProgramInventory
import me.pinfort.tsvideos.catalog.api.SplittedFileCommand
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.ProgramSearchQuery
import me.pinfort.tsvideos.library.TestLoggerConfig
import me.pinfort.tsvideos.library.domain.LibraryProgramFilter
import me.pinfort.tsvideos.storage.api.NasComponent
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
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import javax.sql.DataSource

/**
 * Library reads over catalog's public operations as Spring wires them (one shared transaction manager): library reads
 * joining the caller's snapshot, and library search compatibility with the catalog search. Catalog's own transaction
 * contracts are covered by catalog's CatalogTransactionTest; the bulk apply by library's BulkCatalogTransactionTest.
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
    ProgramCommand::class,
    ExecutedFileCommand::class,
    CreatedFileCommand::class,
    SplittedFileCommand::class,
    ExecutedFileTagCommand::class,
    DirectoryNameComponent::class,
    NormalizeComponent::class,
    TestLoggerConfig::class,
    LibraryProgramSearchCommand::class,
    LibraryCatalogTransactionTest.Nas::class,
)
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class LibraryCatalogTransactionTest : ExpectSpec() {
    @TestConfiguration
    class Nas {
        @Bean
        fun nasComponent(): NasComponent = mockk()
    }

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Autowired private lateinit var snapshots: LedgerSnapshots

    @Autowired private lateinit var inventory: ProgramInventory

    @Autowired private lateinit var programs: ProgramCommand

    @Autowired private lateinit var librarySearch: LibraryProgramSearchCommand

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

    init {
        // Built on use: the transaction manager is injected only after this spec body has run.
        val snapshotRead by lazy {
            TransactionTemplate(transactions).apply {
                isReadOnly = true
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            }
        }

        beforeTest { clear() }
        // Leave the shared container empty so later specs' fixed IDs don't collide.
        afterTest { clear() }

        context("reads") {
            expect("join the caller's repeatable-read snapshot") {
                seed()
                sql(
                    listOf(
                        "INSERT INTO library_collection(id,name,name_key) VALUES(900,'all','all')",
                        "INSERT INTO library_collection_program(label_id,program_id) VALUES(900,4)",
                    ),
                )
                val collection = LibraryProgramFilter(collectionId = 900)
                snapshotRead.execute {
                    inventory.allIds() shouldBe listOf(4L)
                    sql(
                        listOf(
                            "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                                "VALUES(5,'late.m2ts',0,1,'2026-01-02','1','late','channel',1,'SPLITTED')",
                            "INSERT INTO program(id,name,executed_file_id,status) VALUES(6,'late',5,'COMPLETED')",
                            "INSERT INTO library_collection_program(label_id,program_id) VALUES(900,6)",
                        ),
                    )
                    inventory.allIds() shouldBe listOf(4L)
                    inventory.exists(6) shouldBe false
                    inventory.summaries(listOf(4, 6)).map { it.id } shouldBe listOf(4L)
                    snapshots.programSnapshot(6) shouldBe null
                    librarySearch.search(ProgramSearchQuery()).total shouldBe 1
                    // The filtered library query joins the same snapshot for both its page and its count.
                    librarySearch.search(ProgramSearchQuery(), collection).let { page ->
                        page.items.map { it.id } shouldBe listOf(4L)
                        page.total shouldBe 1
                    }
                }
                inventory.allIds() shouldBe listOf(4L, 6L)
                librarySearch.search(ProgramSearchQuery(), collection).total shouldBe 2
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
                        "INSERT INTO library_collection(id,name,name_key) VALUES(902,'subset','subset')",
                        "INSERT INTO library_collection_program(label_id,program_id) VALUES(902,1),(902,2),(902,3),(902,5)",
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
                        ProgramSearchQuery(minDrops = 2),
                        ProgramSearchQuery(maxDrops = 2),
                        ProgramSearchQuery(fileType = ProgramSearchQuery.FileType.TS),
                        ProgramSearchQuery(fileType = ProgramSearchQuery.FileType.ENCODED),
                        // Every ledger condition at once, with a non-default order.
                        ProgramSearchQuery(
                            name = "news",
                            channel = "BS1",
                            recordedFrom = LocalDateTime.parse("2026-10-01T00:00:00"),
                            recordedTo = LocalDateTime.parse("2026-10-05T00:00:00"),
                            status = Program.Status.COMPLETED,
                            minDrops = 0,
                            maxDrops = 9,
                            fileType = ProgramSearchQuery.FileType.TS,
                            sort = ProgramSearchQuery.Sort.SIZE,
                            direction = ProgramSearchQuery.Direction.ASC,
                        ),
                    )

            expect("with a collection holding every program equals the catalog search, including order, total and paging") {
                seedSearch()
                for (query in queries) {
                    val total = programs.search(query).total.toInt()
                    val pages =
                        listOf(
                            query,
                            query.copy(limit = 2, offset = 1),
                            query.copy(limit = 2, offset = total),
                            query.copy(limit = 2, offset = total + 3),
                        )
                    for (page in pages) {
                        librarySearch.search(page, LibraryProgramFilter(collectionId = 900)) shouldBe programs.search(page)
                    }
                }
            }

            expect("narrows the catalog result to each collection/tag combination and keeps its order, total and paging") {
                seedSearch()
                val filters =
                    mapOf(
                        LibraryProgramFilter(collectionId = 902) to setOf(1L, 2L, 3L, 5L),
                        LibraryProgramFilter(libraryTagId = 901) to setOf(2L, 4L, 6L),
                        LibraryProgramFilter(collectionId = 900, libraryTagId = 901) to setOf(2L, 4L, 6L),
                        LibraryProgramFilter(collectionId = 902, libraryTagId = 901) to setOf(2L),
                    )
                for ((filter, members) in filters) {
                    for (query in queries) {
                        val full = programs.search(query.copy(limit = 500, offset = 0)).items.filter { it.id in members }
                        librarySearch.search(query, filter).items shouldBe full
                        // Interior, last, at-end and beyond-end pages; total stays the full filtered count throughout.
                        for (offset in listOf(1, full.size - 1, full.size, full.size + 3).filter { it >= 0 }.distinct()) {
                            val page = librarySearch.search(query.copy(limit = 1, offset = offset), filter)
                            page.items shouldBe full.drop(offset).take(1)
                            page.total shouldBe full.size.toLong()
                            page.limit shouldBe 1
                            page.offset shouldBe offset
                        }
                    }
                }
                librarySearch.search(ProgramSearchQuery(), LibraryProgramFilter(libraryTagId = 999)).total shouldBe 0
            }
        }
    }
}
