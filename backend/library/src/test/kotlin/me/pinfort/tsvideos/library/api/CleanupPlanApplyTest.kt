package me.pinfort.tsvideos.library.api

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Called
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
import me.pinfort.tsvideos.catalog.api.ProgramInventory
import me.pinfort.tsvideos.catalog.api.SplittedFileCommand
import me.pinfort.tsvideos.library.TestLoggerConfig
import me.pinfort.tsvideos.library.domain.BulkAction
import me.pinfort.tsvideos.library.domain.BulkApplyRequest
import me.pinfort.tsvideos.library.domain.BulkInterruptedException
import me.pinfort.tsvideos.library.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.library.domain.BulkRequest
import me.pinfort.tsvideos.library.domain.BulkResult
import me.pinfort.tsvideos.library.domain.CleanupCriteria
import me.pinfort.tsvideos.library.domain.CleanupMode
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Cleanup plans applied through the real bulk command and catalog operations, as Spring wires them, against the
 * database; NAS is a mock. Moved from core's CleanupPlanMapperTest when the ledger mappers moved into catalog.
 */
@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
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
    DuplicateProgramCommand::class,
    DuplicateReviewCommand::class,
    CleanupPlanCommand::class,
    CleanupPlanApplyTest.Nas::class,
)
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CleanupPlanApplyTest : ExpectSpec() {
    @TestConfiguration
    class Nas {
        @Bean
        fun nasComponent(): NasComponent = mockk()
    }

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var inventory: ProgramInventory

    @Autowired private lateinit var cleanup: CleanupPlanCommand

    @Autowired private lateinit var bulkCommand: BulkLibraryCommand

    @Autowired private lateinit var nasComponent: NasComponent

    private fun sql(statement: String) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.executeUpdate(statement) }
        }
    }

    private fun seed(
        count: Int = 2,
        files: Boolean = true,
    ) {
        listOf("created_file", "splitted_file", "program", "executed_file").forEach { sql("DELETE FROM $it") }
        (1..count).forEach { id ->
            sql(
                "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) VALUES($id,'original$id.m2ts',0,10,'2026-01-01','1','title','channel',60,'SPLITTED')",
            )
            sql("INSERT INTO program(id,name,executed_file_id,status) VALUES($id,'program$id',$id,'COMPLETED')")
            if (files) {
                sql(
                    "INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES($id,$id,'split$id',10,60,'ENCODE_TASK_ADDED')",
                )
                sql("INSERT INTO created_file(id,splitted_file_id,file,size,status) VALUES($id,$id,'base/a/p$id/file.mp4',10,'FILE_MOVED')")
            }
        }
    }

    // The NAS mock is the context's bean, so it is reset for each use.
    private fun nas(): NasComponent =
        nasComponent.also {
            clearMocks(it)
            every { it.deleteResource(any()) } returns NasStore.VIDEO_STORE_NAS
        }

    init {
        expect("invalid schemas IDs criteria and oversized selections are rejected before NAS") {
            seed()
            val nas = nas()
            val command = cleanup
            val plan = command.plan(CleanupCriteria(CleanupMode.DUPLICATES))
            val target = plan.targets.single()
            for (invalid in listOf(
                plan.copy(schemaVersion = 2),
                plan.copy(action = "MOVE"),
                plan.copy(targets = listOf(target, target)),
                plan.copy(targets = listOf(target.copy(id = -1))),
                plan.copy(criteria = CleanupCriteria(CleanupMode.RETENTION)),
            )) {
                io.kotest.assertions.throwables
                    .shouldThrow<IllegalArgumentException> { command.apply(invalid) }
            }
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                command.plan(CleanupCriteria(CleanupMode.DUPLICATES, keepIds = listOf(1, 2)))
            }
            // One duplicate group: N members yield N - 1 targets, so 101 members is exactly the 100-target limit.
            seed(101, false)
            command.plan(CleanupCriteria(CleanupMode.DUPLICATES)).targets.size shouldBe 100
            seed(102, false)
            io.kotest.assertions.throwables
                .shouldThrow<IllegalArgumentException> { command.plan(CleanupCriteria(CleanupMode.DUPLICATES)) }
            verify { nas wasNot Called }
        }
        expect("plan and dry run preserve all metadata and never contact NAS") {
            seed(3)
            val nas = nas()
            val command = cleanup
            val plan = command.plan(CleanupCriteria(CleanupMode.DUPLICATES))
            plan.targets.map { it.id } shouldBe listOf(2L, 3L)
            plan.keptTargets.map { it.id } shouldBe listOf(1L)
            plan.targets.first().originalFile shouldBe "original2.m2ts"
            plan.targets.first().estimatedMetadataBytes shouldBe 10L
            command.apply(plan, true).outcomes.map { it.status } shouldBe listOf(BulkOutcomeStatus.SUCCESS, BulkOutcomeStatus.SUCCESS)
            inventory.allIds() shouldBe listOf(1L, 2L, 3L)
            verify { nas wasNot Called }
            command.apply(plan).outcomes.map { it.status } shouldBe listOf(BulkOutcomeStatus.SUCCESS, BulkOutcomeStatus.SUCCESS)
            inventory.allIds() shouldBe listOf(1L)
        }
        expect("changed kept copy, target, or group invalidates reviewed plan") {
            for (change in listOf(
                "UPDATE executed_file SET drops=9 WHERE id=1",
                "UPDATE program SET name='changed' WHERE id=2",
                "INSERT INTO program(id,name,executed_file_id,status) VALUES(3,'alias',1,'COMPLETED')",
            )) {
                seed()
                val nas = nas()
                val command = cleanup
                val plan = command.plan(CleanupCriteria(CleanupMode.DUPLICATES))
                sql(change)
                command
                    .apply(plan)
                    .outcomes
                    .single()
                    .status shouldBe BulkOutcomeStatus.STALE
                verify { nas wasNot Called }
            }
        }
        expect("review metadata tampering never becomes deletion authority") {
            seed()
            val nas = nas()
            val command = cleanup
            val plan = command.plan(CleanupCriteria(CleanupMode.DUPLICATES))
            val target = plan.targets.single()
            command
                .apply(
                    plan.copy(
                        targets =
                            listOf(
                                target.copy(
                                    createdFiles =
                                        target.createdFiles.map {
                                            it.copy(file = "another/file")
                                        },
                                ),
                            ),
                    ),
                ).outcomes
                .single()
                .status shouldBe
                BulkOutcomeStatus.STALE
            verify { nas wasNot Called }
        }
        expect("retention is exclusive, exact channel and excludes unknown recording dates") {
            seed(3)
            sql("UPDATE executed_file SET recorded_at='2025-12-31' WHERE id=1")
            sql("UPDATE executed_file SET channel='2',recorded_at='2025-12-31' WHERE id=2")
            sql("DELETE FROM executed_file WHERE id=3")
            nas()
            val command = cleanup
            command.plan(CleanupCriteria(CleanupMode.RETENTION, "2026-01-01T00:00:00", "1")).targets.map { it.id } shouldBe listOf(1L)
            command.plan(CleanupCriteria(CleanupMode.RETENTION, "2025-12-31T00:00:00")).targets shouldBe emptyList()
        }
        for (files in listOf(false, true)) {
            expect("concurrent opposite keep plans preserve one copy with files=$files") {
                seed(files = files)
                nas()
                val command = cleanup
                val left = command.plan(CleanupCriteria(CleanupMode.DUPLICATES, keepIds = listOf(1)))
                val right = command.plan(CleanupCriteria(CleanupMode.DUPLICATES, keepIds = listOf(2)))
                val start = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val futures =
                        listOf(left, right).map { plan ->
                            executor.submit<BulkResult> {
                                check(start.await(10, TimeUnit.SECONDS))
                                command.apply(plan)
                            }
                        }
                    start.countDown()
                    val outcomes = futures.flatMap { it.get(20, TimeUnit.SECONDS).outcomes }
                    outcomes.count { it.status == BulkOutcomeStatus.SUCCESS } shouldBe 1
                    outcomes.count { it.status == BulkOutcomeStatus.STALE } shouldBe 1
                    inventory.allIds().size shouldBe 1
                } finally {
                    executor.shutdownNow()
                }
            }
        }
        expect("web deletion holding file locks makes waiting cleanup reject missing keeper") {
            seed()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val nas = nas()
            every { nas.deleteResource("base/a/p1/file.mp4") } answers {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                NasStore.VIDEO_STORE_NAS
            }
            val command = cleanup
            val bulk = bulkCommand
            val reviewed = command.plan(CleanupCriteria(CleanupMode.DUPLICATES))
            val request = BulkRequest(listOf(1), BulkAction.DELETE)
            val preview = bulk.preview(request)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val web =
                    executor.submit<BulkResult> {
                        bulk.apply(
                            BulkApplyRequest(
                                request,
                                preview.targets.associate {
                                    it.id to
                                        it.fingerprint
                                },
                            ),
                        )
                    }
                check(entered.await(10, TimeUnit.SECONDS))
                val cli = executor.submit<BulkResult> { command.apply(reviewed) }
                release.countDown()
                web
                    .get(20, TimeUnit.SECONDS)
                    .outcomes
                    .single()
                    .status shouldBe BulkOutcomeStatus.SUCCESS
                cli
                    .get(20, TimeUnit.SECONDS)
                    .outcomes
                    .single()
                    .status shouldBe BulkOutcomeStatus.STALE
                inventory.allIds() shouldBe listOf(2L)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
        expect("interruption rolls back the current target and never starts a later deletion") {
            seed(3)
            val nas = nas()
            every { nas.deleteResource("base/a/p2/file.mp4") } throws InterruptedException("cancel")
            val command = cleanup
            val plan = command.plan(CleanupCriteria(CleanupMode.DUPLICATES))
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor
                    .submit<Boolean> {
                        try {
                            command.apply(plan)
                            false
                        } catch (_: InterruptedException) {
                            Thread.currentThread().isInterrupted
                        } finally {
                            Thread.interrupted()
                        }
                    }.get(20, TimeUnit.SECONDS) shouldBe true
                inventory.allIds() shouldBe listOf(1L, 2L, 3L)
                verify(exactly = 0) { nas.deleteResource("base/a/p3/file.mp4") }
                executor
                    .submit<Boolean> {
                        Thread.currentThread().interrupt()
                        try {
                            command.apply(plan)
                            false
                        } catch (_: InterruptedException) {
                            Thread.currentThread().isInterrupted
                        } finally {
                            Thread.interrupted()
                        }
                    }.get(20, TimeUnit.SECONDS) shouldBe true
                verify(exactly = 1) { nas.deleteResource(any()) }
            } finally {
                executor.shutdownNow()
            }
        }
        expect("interruption after a deletion reports the completed outcomes and the interrupted target") {
            seed(3)
            val nas = nas()
            every { nas.deleteResource("base/a/p3/file.mp4") } throws InterruptedException("cancel")
            val command = cleanup
            val plan = command.plan(CleanupCriteria(CleanupMode.DUPLICATES))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val interrupted =
                    executor
                        .submit<BulkInterruptedException> {
                            try {
                                command.apply(plan)
                                null
                            } catch (error: BulkInterruptedException) {
                                error
                            } finally {
                                Thread.interrupted()
                            }
                        }.get(20, TimeUnit.SECONDS)
                        .shouldNotBeNull()
                interrupted.completed.map { it.id to it.status } shouldBe listOf(2L to BulkOutcomeStatus.SUCCESS)
                interrupted.interruptedId shouldBe 3L
                inventory.allIds() shouldBe listOf(1L, 3L)
            } finally {
                executor.shutdownNow()
            }
        }
        expect("one NAS failure remains explicit while independent later target succeeds") {
            seed(3)
            val nas = nas()
            every { nas.deleteResource("base/a/p2/file.mp4") } throws IllegalStateException("NAS unavailable")
            val command = cleanup
            val plan = command.plan(CleanupCriteria(CleanupMode.DUPLICATES))
            command.apply(plan).outcomes.map { it.status } shouldBe listOf(BulkOutcomeStatus.FAILED, BulkOutcomeStatus.SUCCESS)
            inventory.allIds() shouldBe listOf(1L, 2L)
        }
    }
}
