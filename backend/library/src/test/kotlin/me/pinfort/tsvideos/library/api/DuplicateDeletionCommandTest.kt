package me.pinfort.tsvideos.library.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
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
import me.pinfort.tsvideos.library.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.library.domain.BulkRequest
import me.pinfort.tsvideos.library.domain.BulkResult
import me.pinfort.tsvideos.library.domain.DuplicateDeletionApplyRequest
import me.pinfort.tsvideos.library.domain.DuplicateDeletionGroup
import me.pinfort.tsvideos.library.domain.DuplicateDeletionPlan
import me.pinfort.tsvideos.library.domain.DuplicateDeletionRequest
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import me.pinfort.tsvideos.library.domain.InvalidBulkRequestException
import me.pinfort.tsvideos.library.domain.StaleDuplicateGroupException
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
 * Duplicate-review deletion through the real bulk command, ledger locks and catalog operations against MariaDB; NAS is
 * a mock. Proves the server keeps at least one member of each group, not only that the UI warns.
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
    DuplicateDeletionCommand::class,
    DuplicateDeletionCommandTest.Nas::class,
)
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DuplicateDeletionCommandTest : ExpectSpec() {
    @TestConfiguration
    class Nas {
        @Bean
        fun nasComponent(): NasComponent = mockk()
    }

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var inventory: ProgramInventory

    @Autowired private lateinit var command: DuplicateDeletionCommand

    @Autowired private lateinit var bulk: BulkLibraryCommand

    @Autowired private lateinit var nasComponent: NasComponent

    private fun sql(statement: String) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.executeUpdate(statement) }
        }
    }

    private fun clear() = listOf("created_file", "splitted_file", "program", "executed_file").forEach { sql("DELETE FROM $it") }

    /** One recording per entry: id to (recorded_at, duration, title). */
    private fun seed(
        rows: Map<Long, Triple<String, Int, String>>,
        files: Boolean = true,
    ) {
        clear()
        rows.forEach { (id, row) ->
            val (recordedAt, duration, title) = row
            sql(
                "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                    "VALUES($id,'original$id.m2ts',0,10,'$recordedAt','1','$title','channel',$duration,'SPLITTED')",
            )
            sql("INSERT INTO program(id,name,executed_file_id,status) VALUES($id,'program$id',$id,'COMPLETED')")
            if (files) {
                sql(
                    "INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) " +
                        "VALUES($id,$id,'split$id',10,60,'ENCODE_TASK_ADDED')",
                )
                sql("INSERT INTO created_file(id,splitted_file_id,file,size,status) VALUES($id,$id,'base/a/p$id/file.mp4',10,'FILE_MOVED')")
            }
        }
    }

    /** [count] recordings of the same broadcast. */
    private fun sameBroadcast(
        count: Int = 2,
        files: Boolean = true,
    ) = seed((1L..count).associateWith { Triple("2026-01-01 00:00:00", 60, "title") }, files)

    private fun nas(): NasComponent =
        nasComponent.also {
            clearMocks(it)
            every { it.deleteResource(any()) } returns NasStore.VIDEO_STORE_NAS
        }

    private fun group(
        members: List<Long>,
        deletes: List<Long>,
        reason: DuplicateGroup.Reason = DuplicateGroup.Reason.SAME_BROADCAST,
    ) = DuplicateDeletionGroup(reason, members, deletes)

    private fun preview(vararg groups: DuplicateDeletionGroup) = command.preview(DuplicateDeletionRequest(groups.toList()))

    private fun applyRequest(plan: DuplicateDeletionPlan) =
        DuplicateDeletionApplyRequest(
            plan.groups.map { DuplicateDeletionGroup(it.reason, it.programIds, it.deleteIds) },
            plan.targets.associate { it.id to it.fingerprint },
            plan.keptTargets.associate { it.id to it.fingerprint },
        )

    private fun apply(plan: DuplicateDeletionPlan) = command.apply(applyRequest(plan))

    init {
        afterTest { clear() }

        expect("preview lists the targets and kept snapshots and never contacts NAS") {
            sameBroadcast(3)
            val nas = nas()
            val plan = preview(group(listOf(3, 1, 2), listOf(3)))
            plan.request shouldBe BulkRequest(listOf(3), BulkAction.DELETE)
            plan.targets.map { it.id } shouldBe listOf(3L)
            plan.keptTargets.map { it.id } shouldBe listOf(1L, 2L)
            plan.groups.single().keptIds shouldBe listOf(1L, 2L)
            plan.targets.single().eligible shouldBe true
            verify { nas wasNot Called }
            inventory.allIds() shouldBe listOf(1L, 2L, 3L)
        }

        expect("an all-selected group is rejected by preview and by a direct apply, before any lock or NAS call") {
            sameBroadcast()
            val nas = nas()
            shouldThrow<InvalidBulkRequestException> { preview(group(listOf(1, 2), listOf(1, 2))) }
            // A forged apply naming every member with valid-looking fingerprints is refused too.
            val plan = preview(group(listOf(1, 2), listOf(2)))
            val forged =
                DuplicateDeletionApplyRequest(
                    listOf(group(listOf(1, 2), listOf(1, 2))),
                    mapOf(1L to plan.keptTargets.single().fingerprint, 2L to plan.targets.single().fingerprint),
                    emptyMap(),
                )
            shouldThrow<InvalidBulkRequestException> { command.apply(forged) }
            verify { nas wasNot Called }
            inventory.allIds() shouldBe listOf(1L, 2L)
        }

        expect("malformed, foreign and missing-fingerprint requests are rejected") {
            sameBroadcast(3)
            nas()
            listOf(
                listOf(group(listOf(1), listOf(1))),
                listOf(group(listOf(1, 2), emptyList())),
                listOf(group(listOf(1, 2), listOf(9))),
                listOf(group(listOf(1, 1, 2), listOf(2))),
                listOf(group(listOf(0, 1), listOf(1))),
                listOf(group(listOf(1, 2), listOf(2)), group(listOf(2, 3), listOf(3))),
                emptyList(),
            ).forEach { groups -> shouldThrow<InvalidBulkRequestException> { command.preview(DuplicateDeletionRequest(groups)) } }
            val plan = preview(group(listOf(1, 2, 3), listOf(3)))
            shouldThrow<InvalidBulkRequestException> { command.apply(applyRequest(plan).copy(keptFingerprints = emptyMap())) }
            shouldThrow<InvalidBulkRequestException> { command.apply(applyRequest(plan).copy(fingerprints = emptyMap())) }
            inventory.allIds() shouldBe listOf(1L, 2L, 3L)
        }

        expect("a partial, extended or wrong-reason group is stale at preview") {
            sameBroadcast(3)
            nas()
            listOf(
                group(listOf(1, 2), listOf(2)),
                group(listOf(1, 2, 3, 4), listOf(4)),
                group(listOf(1, 2, 3), listOf(3), DuplicateGroup.Reason.REBROADCAST),
            ).forEach { shouldThrow<StaleDuplicateGroupException> { preview(it) } }
        }

        expect("apply deletes only the selected members and keeps the rest, including a rebroadcast group") {
            seed(
                mapOf(
                    1L to Triple("2026-01-01 00:00:00", 1800, "番組 #1[新]"),
                    2L to Triple("2026-04-01 00:00:00", 1801, "番組 #1[再]"),
                    3L to Triple("2026-01-08 00:00:00", 1800, "番組 #2"),
                    4L to Triple("2026-01-08 00:00:00", 1800, "番組 #2"),
                    5L to Triple("2026-01-08 00:00:00", 1800, "番組 #2"),
                ),
            )
            val nas = nas()
            val plan =
                preview(
                    group(listOf(1, 2), listOf(1), DuplicateGroup.Reason.REBROADCAST),
                    group(listOf(3, 4, 5), listOf(4)),
                )
            apply(plan).outcomes.map { it.id to it.status } shouldBe
                listOf(1L to BulkOutcomeStatus.SUCCESS, 4L to BulkOutcomeStatus.SUCCESS)
            inventory.allIds() shouldBe listOf(2L, 3L, 5L)
            verify(exactly = 1) { nas.deleteResource("base/a/p1/file.mp4") }
            verify(exactly = 1) { nas.deleteResource("base/a/p4/file.mp4") }
            verify(exactly = 2) { nas.deleteResource(any()) }
        }

        expect("a changed or deleted kept recording makes the target STALE without touching NAS") {
            for (change in listOf("UPDATE executed_file SET drops=9 WHERE id=1", "DELETE FROM program WHERE id=1")) {
                sameBroadcast(3)
                val nas = nas()
                val plan = preview(group(listOf(1, 2, 3), listOf(2, 3)))
                sql(change)
                apply(plan).outcomes.map { it.status } shouldBe listOf(BulkOutcomeStatus.STALE, BulkOutcomeStatus.STALE)
                verify { nas wasNot Called }
                inventory.allIds().containsAll(listOf(2L, 3L)) shouldBe true
            }
        }

        expect("a group that changes after a successful deletion stops the remaining targets") {
            // 1-2 and 2-3 overlap enough, 1-3 does not: deleting 2 splits the OVERLAP group, so 3 must stay.
            seed(
                mapOf(
                    1L to Triple("2026-01-01 00:00:00", 60, "title"),
                    2L to Triple("2026-01-01 00:00:10", 60, "title"),
                    3L to Triple("2026-01-01 00:00:20", 60, "title"),
                ),
            )
            nas()
            val plan = preview(group(listOf(1, 2, 3), listOf(2, 3), DuplicateGroup.Reason.OVERLAP))
            apply(plan).outcomes.map { it.id to it.status } shouldBe
                listOf(2L to BulkOutcomeStatus.SUCCESS, 3L to BulkOutcomeStatus.STALE)
            inventory.allIds() shouldBe listOf(1L, 3L)
        }

        expect("a member added to the group after preview makes the target STALE") {
            sameBroadcast()
            val nas = nas()
            val plan = preview(group(listOf(1, 2), listOf(2)))
            sql(
                "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                    "VALUES(3,'original3.m2ts',0,10,'2026-01-01 00:00:00','1','title','channel',60,'SPLITTED')",
            )
            sql("INSERT INTO program(id,name,executed_file_id,status) VALUES(3,'program3',3,'COMPLETED')")
            apply(plan).outcomes.single().status shouldBe BulkOutcomeStatus.STALE
            verify { nas wasNot Called }
        }

        for (files in listOf(false, true)) {
            expect("concurrent requests deleting each other's kept copy leave one recording with files=$files") {
                sameBroadcast(files = files)
                nas()
                val left = preview(group(listOf(1, 2), listOf(2)))
                val right = preview(group(listOf(1, 2), listOf(1)))
                val start = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val futures =
                        listOf(left, right).map { plan ->
                            executor.submit<BulkResult> {
                                check(start.await(10, TimeUnit.SECONDS))
                                apply(plan)
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

        expect("a web bulk deletion of the kept copy, holding its locks, makes the waiting duplicate deletion STALE") {
            sameBroadcast()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val nas = nas()
            every { nas.deleteResource("base/a/p1/file.mp4") } answers {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                NasStore.VIDEO_STORE_NAS
            }
            val reviewed = preview(group(listOf(1, 2), listOf(2)))
            val request = BulkRequest(listOf(1), BulkAction.DELETE)
            val webPreview = bulk.preview(request)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val fingerprints = webPreview.targets.associate { it.id to it.fingerprint }
                val web = executor.submit<BulkResult> { bulk.apply(BulkApplyRequest(request, fingerprints)) }
                check(entered.await(10, TimeUnit.SECONDS))
                val duplicate = executor.submit<BulkResult> { apply(reviewed) }
                release.countDown()
                web
                    .get(20, TimeUnit.SECONDS)
                    .outcomes
                    .single()
                    .status shouldBe BulkOutcomeStatus.SUCCESS
                duplicate
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

        expect("an interruption is reported per target instead of thrown, and no later target is started") {
            sameBroadcast(4)
            val nas = nas()
            every { nas.deleteResource("base/a/p3/file.mp4") } throws InterruptedException("cancel")
            val plan = preview(group(listOf(1, 2, 3, 4), listOf(2, 3, 4)))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result =
                    executor
                        .submit<BulkResult> {
                            try {
                                apply(plan)
                            } finally {
                                Thread.interrupted()
                            }
                        }.get(20, TimeUnit.SECONDS)
                result.outcomes.map { it.id to it.status } shouldBe
                    listOf(2L to BulkOutcomeStatus.SUCCESS, 3L to BulkOutcomeStatus.FAILED, 4L to BulkOutcomeStatus.FAILED)
                result.outcomes[2].message shouldBe "Interrupted before this target; nothing was changed."
                inventory.allIds() shouldBe listOf(1L, 3L, 4L)
                verify(exactly = 0) { nas.deleteResource("base/a/p4/file.mp4") }
            } finally {
                executor.shutdownNow()
            }
        }

        expect("a NAS failure on one target is reported FAILED and an independent group still proceeds") {
            seed(
                mapOf(
                    1L to Triple("2026-01-01 00:00:00", 60, "title"),
                    2L to Triple("2026-01-01 00:00:00", 60, "title"),
                    3L to Triple("2026-01-02 00:00:00", 60, "title"),
                    4L to Triple("2026-01-02 00:00:00", 60, "title"),
                ),
            )
            val nas = nas()
            every { nas.deleteResource("base/a/p2/file.mp4") } throws IllegalStateException("NAS unavailable")
            val plan = preview(group(listOf(1, 2), listOf(2)), group(listOf(3, 4), listOf(4)))
            apply(plan).outcomes.map { it.id to it.status } shouldBe
                listOf(2L to BulkOutcomeStatus.FAILED, 4L to BulkOutcomeStatus.SUCCESS)
            inventory.allIds() shouldBe listOf(1L, 2L, 3L)
        }
    }
}
