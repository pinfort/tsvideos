package me.pinfort.tsvideos.library.api

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.catalog.api.CreatedFileCommand
import me.pinfort.tsvideos.catalog.api.DirectoryNameComponent
import me.pinfort.tsvideos.catalog.api.ExecutedFileCommand
import me.pinfort.tsvideos.catalog.api.ExecutedFileTagCommand
import me.pinfort.tsvideos.catalog.api.LedgerLocks
import me.pinfort.tsvideos.catalog.api.LedgerSnapshots
import me.pinfort.tsvideos.catalog.api.NormalizeComponent
import me.pinfort.tsvideos.catalog.api.ProgramCommand
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

/** Bulk delete over the real catalog operations and database; NAS is a mock. Moved out of catalog's BulkLibraryMapperTest. */
@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@Import(
    LedgerLocks::class,
    LedgerSnapshots::class,
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

    private fun sql(statement: String) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.executeUpdate(statement) }
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
    }
}
