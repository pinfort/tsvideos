package me.pinfort.tsvideos.library.api

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import javax.sql.DataSource

/**
 * The bulk apply over catalog's public operations as Spring wires them (one shared transaction manager): catalog's
 * MANDATORY locks run in its per-target transaction. Library reads joining the caller's snapshot and the library search's
 * compatibility with the catalog search are covered by LibraryCatalogTransactionTest, catalog's own transaction contracts
 * by catalog's CatalogTransactionTest.
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
    BulkLibraryCommand::class,
    BulkCatalogTransactionTest.Nas::class,
)
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class BulkCatalogTransactionTest : ExpectSpec() {
    @TestConfiguration
    class Nas {
        @Bean
        fun nasComponent(): NasComponent = mockk()
    }

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var bulk: BulkLibraryCommand

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

    init {
        beforeTest {
            clear()
            clearMocks(nas)
            every { nas.deleteResource(any()) } returns NasStore.VIDEO_STORE_NAS
        }
        // Leave the shared container empty so later specs' fixed IDs don't collide.
        afterTest { clear() }

        context("coordinated changes") {
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
    }
}
