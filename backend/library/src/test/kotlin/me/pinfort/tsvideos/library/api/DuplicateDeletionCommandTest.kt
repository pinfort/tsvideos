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
import io.mockk.spyk
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
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

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
    DuplicateDeletionCommand::class,
    DuplicateDeletionCommandTest.Beans::class,
)
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DuplicateDeletionCommandTest : ExpectSpec() {
    @TestConfiguration
    class Beans {
        @Bean
        fun nasComponent(): NasComponent = mockk()

        // A spy over the real detection, so a test can see which detection the guard ran.
        @Bean
        fun duplicateProgramCommand(inventory: ProgramInventory): DuplicateProgramCommand = spyk(DuplicateProgramCommand(inventory))
    }

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var inventory: ProgramInventory

    @Autowired private lateinit var command: DuplicateDeletionCommand

    @Autowired private lateinit var bulk: BulkLibraryCommand

    @Autowired private lateinit var nasComponent: NasComponent

    @Autowired private lateinit var duplicates: DuplicateProgramCommand

    @Autowired private lateinit var environment: Environment

    private fun sql(statement: String) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.executeUpdate(statement) }
        }
    }

    private fun clear() = listOf("created_file", "splitted_file", "program", "executed_file").forEach { sql("DELETE FROM $it") }

    /** One recording per entry: id to (recorded_at, duration, title), on channel "1" unless [channels] names another. */
    private fun seed(
        rows: Map<Long, Triple<String, Int, String>>,
        files: Boolean = true,
        channels: Map<Long, String> = emptyMap(),
    ) {
        clear()
        rows.forEach { (id, row) ->
            val (recordedAt, duration, title) = row
            sql(
                "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                    "VALUES($id,'original$id.m2ts',0,10,'$recordedAt','${channels[id] ?: "1"}','$title','channel',$duration,'SPLITTED')",
            )
            sql("INSERT INTO program(id,name,executed_file_id,status) VALUES($id,'program$id',$id,'COMPLETED')")
            if (files) transferredFile(id)
        }
    }

    /** One transferred file of recording [id], at base/a/p[id]/file.mp4. */
    private fun transferredFile(id: Long) {
        sql(
            "INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) " +
                "VALUES($id,$id,'split$id',10,60,'ENCODE_TASK_ADDED')",
        )
        sql("INSERT INTO created_file(id,splitted_file_id,file,size,status) VALUES($id,$id,'base/a/p$id/file.mp4',10,'FILE_MOVED')")
    }

    private data class TimingRow(
        val id: Long,
        val recordedAt: LocalDateTime,
        val channel: String,
        val title: String,
        val duration: Int,
    )

    private fun insertTimingRows(rows: List<TimingRow>) =
        rows.chunked(1_000).forEach { chunk ->
            sql(
                "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) VALUES " +
                    chunk.joinToString(",") {
                        "(${it.id},'original${it.id}.m2ts',0,10,'${TIMESTAMP.format(it.recordedAt)}'," +
                            "'${it.channel}','${it.title}','channel',${it.duration},'SPLITTED')"
                    },
            )
            sql(
                "INSERT INTO program(id,name,executed_file_id,status) VALUES " +
                    chunk.joinToString(",") { "(${it.id},'program${it.id}',${it.id},'COMPLETED')" },
            )
        }

    /**
     * Opt-in (#209): times the guarded duplicate deletion and the whole-library re-detection that an OTHER_CHANNEL
     * target runs under the whole-ledger lock, on a SYNTHETIC library of TSVIDEOS_DUPLICATE_TIMING_ROWS unrelated
     * recordings (default 30,000, an assumed size, not the production one) plus [TIMING_GROUPS] groups of each reason.
     * Only this Testcontainers MariaDB and the mocked NAS are touched; any other database is refused before the first
     * write. Nothing else holds or waits for the lock here, so neither a lock wait nor production latency is measured.
     */
    private fun timing() {
        val url = environment.getProperty("spring.datasource.url").orEmpty()
        check(url.startsWith("jdbc:tc:") && System.getenv("DATABASE_CONNECTION").isNullOrBlank()) {
            "The timing test runs only on the Testcontainers database"
        }
        val unrelated = System.getenv("TSVIDEOS_DUPLICATE_TIMING_ROWS")?.toIntOrNull() ?: 30_000
        check(unrelated > 0) { "TSVIDEOS_DUPLICATE_TIMING_ROWS must be positive" }
        clear()
        val base = LocalDateTime.of(2000, 1, 1, 0, 0)
        // One recording per channel and hour, each with its own title: none groups, but every title has a key.
        insertTimingRows(
            (0 until unrelated).map { i ->
                TimingRow(i + 1L, base.plusHours(i / TIMING_CHANNELS.toLong()), "ch${i % TIMING_CHANNELS}", "番組$i #1", 1800 + i % 7)
            },
        )
        val first = unrelated + 1L

        fun pair(n: Int) = first + 2L * n to first + 2L * n + 1

        val otherPairs = (0 until TIMING_GROUPS).map { pair(it) }
        val samePairs = (0 until TIMING_GROUPS).map { pair(TIMING_GROUPS + it) }
        val designated =
            (0 until TIMING_GROUPS).flatMap { g ->
                val at = base.plusYears(1).plusDays(g.toLong())
                val (rebroadcast, rerun) = pair(2 * TIMING_GROUPS + g)
                listOf(
                    TimingRow(otherPairs[g].first, at, "ch0", "別局$g #1", 1800),
                    TimingRow(otherPairs[g].second, at.plusHours(12), "ch1", "別局$g #1", 1801),
                    TimingRow(samePairs[g].first, at, "ch2", "同一$g #1", 1800),
                    TimingRow(samePairs[g].second, at, "ch2", "同一$g #1", 1800),
                    TimingRow(rebroadcast, at.plusHours(6), "ch3", "再放送$g #1[新]", 1800),
                    TimingRow(rerun, at.plusYears(1).plusHours(6), "ch3", "再放送$g #1[再]", 1801),
                )
            }
        insertTimingRows(designated)
        (otherPairs + samePairs).forEach { (a, b) ->
            transferredFile(a)
            transferredFile(b)
        }
        nas()

        // Also the warm-up for the timings below.
        duplicates.detect().groupingBy { it.reason }.eachCount() shouldBe
            listOf(DuplicateGroup.Reason.SAME_BROADCAST, DuplicateGroup.Reason.REBROADCAST, DuplicateGroup.Reason.OTHER_CHANNEL)
                .associateWith { TIMING_GROUPS }
        val total = inventory.allIds().size
        val read = median { inventory.recordedPrograms() }
        val detect = median { duplicates.detect() }
        val onChannel = median { duplicates.detectOnChannel("ch0") }

        // Each target is its own request: preview, then the guarded apply of one deletion.
        fun guarded(
            pairs: List<Pair<Long, Long>>,
            reason: DuplicateGroup.Reason,
        ): Pair<Duration, Duration> {
            val timings =
                pairs.take(TIMING_TARGETS).map { (kept, deleted) ->
                    val (plan, previewTime) = measureTimedValue { preview(group(listOf(kept, deleted), listOf(deleted), reason)) }
                    val (result, applyTime) = measureTimedValue { apply(plan) }
                    result.outcomes.single().status shouldBe BulkOutcomeStatus.SUCCESS
                    previewTime to applyTime
                }
            return timings.map { it.first }.median() to timings.map { it.second }.median()
        }

        val (otherPreview, otherApply) = guarded(otherPairs, DuplicateGroup.Reason.OTHER_CHANNEL)
        val (samePreview, sameApply) = guarded(samePairs, DuplicateGroup.Reason.SAME_BROADCAST)

        val report =
            buildString {
                appendLine("# #209 guarded duplicate deletion timing (SYNTHETIC library, Testcontainers MariaDB, mocked NAS)")
                appendLine()
                appendLine(
                    "- recordings: $total (synthetic: $unrelated unrelated, plus $TIMING_GROUPS groups of each of " +
                        "OTHER_CHANNEL, SAME_BROADCAST and REBROADCAST); an assumed size, not the production library",
                )
                appendLine("- ProgramInventory.recordedPrograms, the database read alone (median of 5): ${read.inWholeMilliseconds} ms")
                appendLine(
                    "- DuplicateProgramCommand.detect, read + detection = an OTHER_CHANNEL target's re-detection " +
                        "(median of 5): ${detect.inWholeMilliseconds} ms",
                )
                appendLine(
                    "- detectOnChannel(ch0, ${unrelated / TIMING_CHANNELS + TIMING_GROUPS} recordings), the other reasons' " +
                        "re-detection (median of 5): ${onChannel.inWholeMilliseconds} ms",
                )
                appendLine(
                    "- OTHER_CHANNEL, one target per request (median of $TIMING_TARGETS): " +
                        "preview ${otherPreview.inWholeMilliseconds} ms, guarded apply ${otherApply.inWholeMilliseconds} ms",
                )
                appendLine(
                    "- SAME_BROADCAST, one target per request (median of $TIMING_TARGETS): " +
                        "preview ${samePreview.inWholeMilliseconds} ms, guarded apply ${sameApply.inWholeMilliseconds} ms",
                )
                appendLine()
                appendLine(
                    "The guarded apply is one target's transaction: the whole-ledger lock, the kept snapshot, the re-detection, " +
                        "the database deletion and the mocked NAS call. Not measured: waiting for the lock while another ledger " +
                        "writer holds it, the production database, NAS and library size.",
                )
            }
        val outputDir =
            System.getenv("TSVIDEOS_REBROADCAST_REPORT")?.takeIf { it.isNotBlank() }?.let(::File)
                ?: File("build/reports/rebroadcast-validation")
        outputDir.mkdirs()
        File(outputDir, "duplicate-deletion-timing.md").writeText(report)
        println(report)
    }

    private fun median(block: () -> Unit): Duration = List(5) { measureTime(block) }.median()

    private fun List<Duration>.median(): Duration = sorted()[size / 2]

    private companion object {
        const val TIMING_CHANNELS = 24
        const val TIMING_GROUPS = 20
        const val TIMING_TARGETS = 5
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
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

        expect("malformed, foreign and missing-, extra- or wrong-fingerprint requests are rejected before NAS") {
            sameBroadcast(3)
            val nas = nas()
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
            val kept = applyRequest(plan).keptFingerprints
            // A delete target listed as kept, and a kept fingerprint under a recording outside the group.
            val extra = kept + (3L to plan.targets.single().fingerprint)
            val wrong = mapOf(1L to kept.getValue(1L), 9L to kept.getValue(2L))
            listOf(extra, wrong).forEach { keptFingerprints ->
                shouldThrow<InvalidBulkRequestException> { command.apply(applyRequest(plan).copy(keptFingerprints = keptFingerprints)) }
            }
            verify { nas wasNot Called }
            inventory.allIds() shouldBe listOf(1L, 2L, 3L)
        }

        expect("at most 100 recordings to delete and 100 to keep, the shared bulk request limit") {
            clear()
            nas()
            BulkLibraryCommand.MAX_REQUEST_SIZE shouldBe 100
            CleanupPlanCommand.MAX_PLAN_SIZE shouldBe BulkLibraryCommand.MAX_REQUEST_SIZE
            val members = (1L..201L).toList()
            // Over either limit is malformed; exactly at both limits passes validation and only fails detection.
            shouldThrow<InvalidBulkRequestException> { preview(group(members.take(102), members.take(101))) }
            shouldThrow<InvalidBulkRequestException> { preview(group(members.take(102), members.take(1))) }
            shouldThrow<InvalidBulkRequestException> { preview(group(members, members.take(100))) }
            shouldThrow<StaleDuplicateGroupException> { preview(group(members.take(200), members.take(100))) }
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

        expect("each target is re-detected freshly on its kept recordings' channel only, never across the whole library") {
            seed(
                mapOf(
                    1L to Triple("2026-01-01 00:00:00", 60, "title"),
                    2L to Triple("2026-01-01 00:00:00", 60, "title"),
                    3L to Triple("2026-01-01 00:00:00", 60, "title"),
                    4L to Triple("2026-01-01 00:00:00", 60, "title"),
                    5L to Triple("2026-01-01 00:00:00", 60, "title"),
                    6L to Triple("2026-01-01 00:00:00", 1800, "番組 #1[新]"),
                    7L to Triple("2026-04-01 00:00:00", 1801, "番組 #1[再]"),
                ),
                channels = mapOf(4L to "2", 5L to "2", 6L to "3", 7L to "3"),
            )
            nas()
            val plan =
                preview(
                    group(listOf(1, 2, 3), listOf(2, 3)),
                    group(listOf(6, 7), listOf(6), DuplicateGroup.Reason.REBROADCAST),
                )
            // A change on an unrelated channel after the preview does not affect these groups.
            sql(
                "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                    "VALUES(8,'original8.m2ts',0,10,'2026-01-01 00:00:00','2','title','channel',60,'SPLITTED')",
            )
            sql("INSERT INTO program(id,name,executed_file_id,status) VALUES(8,'program8',8,'COMPLETED')")
            clearMocks(duplicates, answers = false)

            apply(plan).outcomes.map { it.id to it.status } shouldBe
                listOf(2L to BulkOutcomeStatus.SUCCESS, 3L to BulkOutcomeStatus.SUCCESS, 6L to BulkOutcomeStatus.SUCCESS)
            // One fresh detection per target (no reuse across target transactions), each on the kept members' channel.
            verify(exactly = 2) { duplicates.detectOnChannel("1", any()) }
            verify(exactly = 1) { duplicates.detectOnChannel("3", any()) }
            verify(exactly = 0) { duplicates.detectOnChannel("2", any()) }
            verify(exactly = 0) { duplicates.detect(any()) }
            inventory.allIds() shouldBe listOf(1L, 4L, 5L, 7L, 8L)
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

        expect("deleting two of three rebroadcasts keeps the chosen non-default copy") {
            // 3 is the longest, so the default keep; the operator keeps 1. After 2 goes, 1 and 3 still pair up.
            seed(
                mapOf(
                    1L to Triple("2026-01-01 00:00:00", 1800, "番組 #1[新]"),
                    2L to Triple("2026-02-01 00:00:00", 1801, "番組 #1[再]"),
                    3L to Triple("2026-03-01 00:00:00", 1802, "番組 #1"),
                ),
            )
            val nas = nas()
            duplicates.detect().single().let {
                it.reason shouldBe DuplicateGroup.Reason.REBROADCAST
                it.programs.first().id shouldBe 3L
            }
            val plan = preview(group(listOf(1, 2, 3), listOf(2, 3), DuplicateGroup.Reason.REBROADCAST))
            apply(plan).outcomes.map { it.id to it.status } shouldBe
                listOf(2L to BulkOutcomeStatus.SUCCESS, 3L to BulkOutcomeStatus.SUCCESS)
            inventory.allIds() shouldBe listOf(1L)
            verify(exactly = 1) { nas.deleteResource("base/a/p2/file.mp4") }
            verify(exactly = 1) { nas.deleteResource("base/a/p3/file.mp4") }
            verify(exactly = 2) { nas.deleteResource(any()) }
        }

        expect("an overlap group that becomes a same-broadcast pair after a deletion stops the remaining targets") {
            // 1 starts 5 s later than 2 and 3; deleting 1 first (lowest id) turns the rest into SAME_BROADCAST 2-3.
            seed(
                mapOf(
                    1L to Triple("2026-01-01 00:00:05", 60, "title"),
                    2L to Triple("2026-01-01 00:00:00", 60, "title"),
                    3L to Triple("2026-01-01 00:00:00", 60, "title"),
                ),
            )
            val nas = nas()
            val plan = preview(group(listOf(1, 2, 3), listOf(1, 3), DuplicateGroup.Reason.OVERLAP))
            apply(plan).outcomes.map { it.id to it.status } shouldBe
                listOf(1L to BulkOutcomeStatus.SUCCESS, 3L to BulkOutcomeStatus.STALE)
            inventory.allIds() shouldBe listOf(2L, 3L)
            verify(exactly = 1) { nas.deleteResource("base/a/p1/file.mp4") }
            verify(exactly = 1) { nas.deleteResource(any()) }
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

        context("other-channel groups (#209)") {
            val otherChannel = DuplicateGroup.Reason.OTHER_CHANNEL

            /** The same episode on channels "1", "2", ... (one recording each), all within the other-channel duration rule. */
            fun acrossChannels(count: Int = 2) =
                seed(
                    (1L..count).associateWith { Triple("2026-01-0$it 00:00:00", 1800 + it.toInt(), "番組 #1") },
                    channels = (1L..count).associate { it to "$it" },
                )

            fun insert(
                id: Long,
                recordedAt: String,
                channel: String,
                title: String = "番組 #1",
                duration: Int = 1801,
            ) {
                sql(
                    "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                        "VALUES($id,'original$id.m2ts',0,10,'$recordedAt','$channel','$title','channel',$duration,'SPLITTED')",
                )
                sql("INSERT INTO program(id,name,executed_file_id,status) VALUES($id,'program$id',$id,'COMPLETED')")
            }

            expect("a cross-channel group is deleted after a whole-library re-detection per target, never a channel-local one") {
                acrossChannels(3)
                val nas = nas()
                duplicates.detect().single().let {
                    it.reason shouldBe otherChannel
                    it.programs.map { program -> program.channel }.toSet() shouldBe setOf("1", "2", "3")
                }
                // The operator keeps 1, which is not the recommended (longest) copy.
                val plan = preview(group(listOf(1, 2, 3), listOf(2, 3), otherChannel))
                clearMocks(duplicates, answers = false)
                apply(plan).outcomes.map { it.id to it.status } shouldBe
                    listOf(2L to BulkOutcomeStatus.SUCCESS, 3L to BulkOutcomeStatus.SUCCESS)
                verify(exactly = 2) { duplicates.detect(any()) }
                verify(exactly = 0) { duplicates.detectOnChannel(any(), any()) }
                inventory.allIds() shouldBe listOf(1L)
                verify(exactly = 1) { nas.deleteResource("base/a/p2/file.mp4") }
                verify(exactly = 1) { nas.deleteResource("base/a/p3/file.mp4") }
            }

            expect("two kept recordings on different channels: the third channel's copy is deleted, not refused as stale") {
                // The channel-local guard assumed the kept members share one channel; an other-channel group must not.
                acrossChannels(3)
                val nas = nas()
                val plan = preview(group(listOf(1, 2, 3), listOf(3), otherChannel))
                plan.groups.single().keptIds shouldBe listOf(1L, 2L)
                plan.keptTargets.mapNotNull { it.snapshot?.program?.channel }.toSet() shouldBe setOf("1", "2")
                clearMocks(duplicates, answers = false)
                apply(plan).outcomes.map { it.id to it.status } shouldBe listOf(3L to BulkOutcomeStatus.SUCCESS)
                verify(exactly = 1) { duplicates.detect(any()) }
                verify(exactly = 0) { duplicates.detectOnChannel(any(), any()) }
                inventory.allIds() shouldBe listOf(1L, 2L)
                verify(exactly = 1) { nas.deleteResource("base/a/p3/file.mp4") }
                verify(exactly = 1) { nas.deleteResource(any()) }
                // Both kept copies stay one other-channel group.
                duplicates.detect().map { group -> group.reason to group.programs.map { it.id }.toSet() } shouldBe
                    listOf(otherChannel to setOf(1L, 2L))
            }

            expect("an all-selected cross-channel group is rejected by preview and by a forged apply") {
                acrossChannels()
                val nas = nas()
                shouldThrow<InvalidBulkRequestException> { preview(group(listOf(1, 2), listOf(1, 2), otherChannel)) }
                val plan = preview(group(listOf(1, 2), listOf(2), otherChannel))
                val forged =
                    DuplicateDeletionApplyRequest(
                        listOf(group(listOf(1, 2), listOf(1, 2), otherChannel)),
                        mapOf(1L to plan.keptTargets.single().fingerprint, 2L to plan.targets.single().fingerprint),
                        emptyMap(),
                    )
                shouldThrow<InvalidBulkRequestException> { command.apply(forged) }
                verify { nas wasNot Called }
                inventory.allIds() shouldBe listOf(1L, 2L)
            }

            expect("a cross-channel group named with another reason, or a reason named for it, is stale at preview") {
                acrossChannels()
                nas()
                shouldThrow<StaleDuplicateGroupException> { preview(group(listOf(1, 2), listOf(2), DuplicateGroup.Reason.REBROADCAST)) }
                sameBroadcast()
                shouldThrow<StaleDuplicateGroupException> { preview(group(listOf(1, 2), listOf(2), otherChannel)) }
            }

            expect("a changed or deleted kept recording makes a cross-channel target STALE without touching NAS") {
                for (change in listOf("UPDATE executed_file SET drops=9 WHERE id=1", "DELETE FROM program WHERE id=1")) {
                    acrossChannels()
                    val nas = nas()
                    val plan = preview(group(listOf(1, 2), listOf(2), otherChannel))
                    sql(change)
                    apply(plan).outcomes.single().status shouldBe BulkOutcomeStatus.STALE
                    verify { nas wasNot Called }
                    inventory.allIds().contains(2L) shouldBe true
                }
            }

            expect("a recording of the episode on a third channel after preview makes the target STALE") {
                acrossChannels()
                val nas = nas()
                val plan = preview(group(listOf(1, 2), listOf(2), otherChannel))
                // Channel "3" is neither kept nor deleted, so only a whole-library re-detection sees it join the group.
                insert(3, "2026-02-01 00:00:00", "3")
                duplicates
                    .detect()
                    .single()
                    .programs
                    .map { it.id }
                    .sorted() shouldBe listOf(1L, 2L, 3L)
                apply(plan).outcomes.single().status shouldBe BulkOutcomeStatus.STALE
                verify { nas wasNot Called }
                inventory.allIds() shouldBe listOf(1L, 2L, 3L)
            }

            expect("a kept recording regrouped as a same-channel rebroadcast after preview makes the target STALE") {
                acrossChannels()
                val nas = nas()
                val plan = preview(group(listOf(1, 2), listOf(2), otherChannel))
                // A rebroadcast of 1 on its own channel: the second stage takes 1 and 3, so the cross-channel group is gone.
                insert(3, "2026-03-01 00:00:00", "1")
                duplicates.detect().map { it.reason } shouldBe listOf(DuplicateGroup.Reason.REBROADCAST)
                apply(plan).outcomes.single().status shouldBe BulkOutcomeStatus.STALE
                verify { nas wasNot Called }
            }

            expect("concurrent requests deleting each other's kept copy across channels leave one recording") {
                acrossChannels()
                nas()
                val left = preview(group(listOf(1, 2), listOf(2), otherChannel))
                val right = preview(group(listOf(1, 2), listOf(1), otherChannel))
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

            expect("a NAS failure on one cross-channel target is FAILED and the next target is still checked against the group") {
                acrossChannels(3)
                val nas = nas()
                every { nas.deleteResource("base/a/p2/file.mp4") } throws IllegalStateException("NAS unavailable")
                val plan = preview(group(listOf(1, 2, 3), listOf(2, 3), otherChannel))
                apply(plan).outcomes.map { it.id to it.status } shouldBe
                    listOf(2L to BulkOutcomeStatus.FAILED, 3L to BulkOutcomeStatus.SUCCESS)
                inventory.allIds() shouldBe listOf(1L, 2L)
            }

            expect("a mixed request keeps the channel-local re-detection for the existing reasons") {
                seed(
                    mapOf(
                        1L to Triple("2026-01-01 00:00:00", 60, "title"),
                        2L to Triple("2026-01-01 00:00:00", 60, "title"),
                        3L to Triple("2026-01-02 00:00:00", 1801, "番組 #1"),
                        4L to Triple("2026-01-03 00:00:00", 1802, "番組 #1"),
                    ),
                    channels = mapOf(4L to "2"),
                )
                nas()
                val plan = preview(group(listOf(1, 2), listOf(2)), group(listOf(3, 4), listOf(3), otherChannel))
                clearMocks(duplicates, answers = false)
                apply(plan).outcomes.map { it.id to it.status } shouldBe
                    listOf(2L to BulkOutcomeStatus.SUCCESS, 3L to BulkOutcomeStatus.SUCCESS)
                verify(exactly = 1) { duplicates.detectOnChannel("1", any()) }
                verify(exactly = 1) { duplicates.detect(any()) }
                inventory.allIds() shouldBe listOf(1L, 4L)
            }
        }

        context("opt-in timing on a synthetic library (TSVIDEOS_DUPLICATE_TIMING=1)") {
            expect("guarded deletion and whole-library re-detection on Testcontainers MariaDB with a mocked NAS")
                .config(enabled = System.getenv("TSVIDEOS_DUPLICATE_TIMING") == "1") { timing() }
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
