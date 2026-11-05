package me.pinfort.tsvideos.library.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import me.pinfort.tsvideos.catalog.api.ProgramInventory
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import java.io.File
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * Optional, read-only validation of the other-channel stage (#209) on saved `tvmcli search --json` output.
 *
 * Disabled unless TSVIDEOS_REBROADCAST_DATA names a directory with `search-*.json` (the same data as
 * RebroadcastDataValidationTest). Only those files are read; nothing contacts a database or NAS. Run from backend/:
 *
 *   $env:TSVIDEOS_REBROADCAST_DATA = "E:/Users/pinfo/Documents/tv/manager"
 *   ./gradlew library:test --tests "me.pinfort.tsvideos.library.api.OtherChannelDataValidationTest"
 *
 * other-channel-report.md is written to TSVIDEOS_REBROADCAST_REPORT (default library/build/reports/rebroadcast-validation/).
 * The tuning pairs (まどか☆マギカ #1–#6) fixed the frame-name and duration rules; #7–#11 and the negatives are the
 * held-out check. Both come from one series, so passing is not production precision. Timings are the in-memory detector
 * ([DuplicateProgramCommand.group]) only, on lists built before timing: the database read and the whole-ledger lock of
 * a real deletion are not included (DuplicateDeletionCommandTest's opt-in timing measures those on Testcontainers MariaDB).
 */
class OtherChannelDataValidationTest :
    ExpectSpec({
        val dataDir = System.getenv("TSVIDEOS_REBROADCAST_DATA")?.takeIf { it.isNotBlank() }?.let(::File)

        context("synthetic replica (committed fixture rows)") {
            expect("6056 ([新] in front of the frame name) keeps its pair with 6065 inside each copy, apart from other copies") {
                val fixtureJson = checkNotNull(javaClass.getResourceAsStream("/rebroadcast/issue-209-fixture.json"))
                val fixture = RebroadcastData.programs(RebroadcastData.mapper.readTree(fixtureJson)).associateBy { it.id }
                val bs = checkNotNull(fixture[6056L])
                val gr = checkNotNull(fixture[6065L])
                val copies = 0 until 3
                val keys =
                    copies.map { copy ->
                        val bsKey = OtherChannelMatcher.key(replica(bs, copy).title)
                        val grKey = OtherChannelMatcher.key(replica(gr, copy).title)
                        checkNotNull(bsKey) shouldBe grKey
                        bsKey.episode shouldBe 1
                        bsKey
                    }
                keys.distinct().size shouldBe copies.count()
                // Detected through the matcher on the replicas: exactly one pair per copy, none across copies.
                OtherChannelMatcher
                    .groups(copies.flatMap { copy -> listOf(replica(bs, copy), replica(gr, copy)) })
                    .map { (_, members) -> members.map { it.id }.toSet() }
                    .toSet() shouldBe copies.map { copy -> setOf(6056L + copy * ID_OFFSET, 6065L + copy * ID_OFFSET) }.toSet()
            }
        }

        context("external #209 data (TSVIDEOS_REBROADCAST_DATA)") {
            expect("tuning and held-out pairs, negatives, disjoint groups, unchanged earlier stages and timings")
                .config(enabled = dataDir != null) {
                    val dir = checkNotNull(dataDir)
                    val files =
                        dir
                            .listFiles { file -> file.isFile && file.name.startsWith("search-") && file.name.endsWith(".json") }
                            .orEmpty()
                            .sorted()
                    check(files.isNotEmpty()) { "no search-*.json in $dir" }
                    val coverage =
                        files.map { file ->
                            val tree = RebroadcastData.readTree(file)
                            file.name to (tree["total"] to tree["programs"]?.size())
                        }
                    val rows = files.flatMap { file -> RebroadcastData.programs(RebroadcastData.readTree(file)) }
                    val byId = rows.groupBy { it.id }
                    byId.filterValues { copies -> copies.distinct().size > 1 }.keys.shouldBeEmpty()
                    val programs = byId.values.map { it.first() }.sortedBy { it.id }

                    val groups = RebroadcastData.detect(programs)
                    val baseline = RebroadcastData.baseline(programs)
                    val groupOf = groups.flatMap { group -> group.programs.map { it.id to group } }.toMap()
                    val other = groups.filter { it.reason == DuplicateGroup.Reason.OTHER_CHANNEL }
                    val pairIds = (TUNING + HELD_OUT).flatten()
                    val negativeIds = NEGATIVES.flatMap { listOf(it.first, it.second) }
                    val missing = (pairIds + negativeIds).filter { it !in byId }
                    val keyed = programs.count { OtherChannelMatcher.key(it.title) != null }

                    // A synthetic library, not real recordings: COPIES replicas of the subset (see replica). Each copy
                    // keeps every relation inside it and cannot group with another copy, so every reason scales exactly.
                    check(programs.all { it.id in 1 until ID_OFFSET }) { "ids must fit below the replica offset" }
                    check(datedSpanDays(programs) < SHIFT_DAYS) { "the subset spans more than the replica shift" }
                    val scaled = (0 until COPIES).flatMap { copy -> programs.map { replica(it, copy) } }
                    val scaledGroups = RebroadcastData.detect(scaled)

                    // Timings of the detector alone; the command and the input lists are built outside the timed blocks.
                    val detector = DuplicateProgramCommand(mockk<ProgramInventory>())
                    val busiest =
                        programs
                            .groupingBy { it.channel }
                            .eachCount()
                            .maxBy { it.value }
                            .key
                    val onChannel = programs.filter { it.channel == busiest }
                    val detect = median { detector.group(programs, RATIO) }
                    val detectOnChannel = median { detector.group(onChannel, RATIO) }
                    val scaledDetect = median(3) { detector.group(scaled, RATIO) }

                    val outputDir =
                        System.getenv("TSVIDEOS_REBROADCAST_REPORT")?.takeIf { it.isNotBlank() }?.let(::File)
                            ?: File("build/reports/rebroadcast-validation")
                    outputDir.mkdirs()
                    val report =
                        buildString {
                            appendLine("# #209 other-channel validation (saved search subset, not the whole library)")
                            appendLine()
                            coverage.forEach { (name, sizes) ->
                                appendLine("- $name: total ${sizes.first}, rows ${sizes.second}")
                            }
                            appendLine("- rows: ${rows.size}, unique program ids: ${programs.size}")
                            appendLine("- channels: ${programs.map { it.channel }.distinct().size}")
                            appendLine("- programs with an other-channel key: $keyed")
                            appendLine("- groups: ${counts(groups)}")
                            appendLine("- ids missing from the data: $missing")
                            appendLine()
                            appendLine("## Timings (in-memory detector only)")
                            appendLine()
                            appendLine("DuplicateProgramCommand.group on lists built before timing: no database read, lock or mock.")
                            appendLine()
                            appendLine("- real subset, ${programs.size} programs (median of 5): ${detect.inWholeMilliseconds} ms")
                            appendLine(
                                "- real subset, busiest channel $busiest only, ${onChannel.size} programs " +
                                    "(detectOnChannel's detector, median of 5): ${detectOnChannel.inWholeMilliseconds} ms",
                            )
                            appendLine(
                                "- SYNTHETIC library, ${scaled.size} programs ($COPIES shifted replicas of the subset, " +
                                    "not real recordings; median of 3): ${scaledDetect.inWholeMilliseconds} ms",
                            )
                            appendLine("- SYNTHETIC library groups: ${counts(scaledGroups)} (expected each real count x $COPIES)")
                            appendLine(
                                "- an OTHER_CHANNEL deletion target re-runs detect() under the whole-ledger lock; " +
                                    "the database read and the lock are not included above",
                            )
                            appendLine()
                            appendLine("## OTHER_CHANNEL groups (real data)")
                            appendLine()
                            appendLine("| kind | ids | channels | comparison title | durations | diff s | ratio |")
                            appendLine("| --- | --- | --- | --- | --- | --- | --- |")
                            other.forEach { group ->
                                val ids = group.programs.map { it.id }.toSet()
                                val kind =
                                    when (ids) {
                                        in TUNING -> "tuning"
                                        in HELD_OUT -> "held-out"
                                        else -> "ADDITIONAL"
                                    }
                                val durations = group.programs.map { it.duration }
                                appendLine(
                                    listOf(
                                        kind,
                                        group.programs.joinToString(",") { it.id.toString() },
                                        group.programs.joinToString(" / ") { it.channel },
                                        group.comparisonTitle.orEmpty(),
                                        durations.joinToString(" / "),
                                        "%.3f".format(durations.max() - durations.min()),
                                        "%.5f".format(durations.min() / durations.max()),
                                    ).joinToString(" | ", "| ", " |"),
                                )
                            }
                            appendLine()
                            appendLine("Titles of ADDITIONAL candidates (a person must review them; grouping is no precision check):")
                            other
                                .filter { group -> group.programs.map { it.id }.toSet() !in TUNING + HELD_OUT }
                                .forEach { group ->
                                    group.programs.forEach { appendLine("- ${it.id} ${it.channel} ${it.recordedAt}: ${it.title}") }
                                }
                        }
                    File(outputDir, "other-channel-report.md").writeText(report)
                    println(report)

                    missing.shouldBeEmpty()
                    RebroadcastData.sharedMembers(groups).shouldBeEmpty()
                    // The first two stages are exactly #208's: same-broadcast/overlap as the frozen baseline, and the
                    // rebroadcast stage over what the baseline leaves.
                    val firstStage = setOf(DuplicateGroup.Reason.SAME_BROADCAST, DuplicateGroup.Reason.OVERLAP)
                    groups.filter { it.reason in firstStage }.map { it.reason to it.programs.map { p -> p.id } } shouldBe
                        baseline.map { it.reason to it.programs.map { p -> p.id } }
                    val inBaseline = baseline.flatMap { group -> group.programs.map { it.id } }.toSet()
                    val secondStage =
                        RebroadcastMatcher
                            .groups(programs.filter { it.id !in inBaseline })
                            .map { (_, members) -> members.map { it.id }.toSet() }
                            .toSet()
                    RebroadcastData.idSets(groups, DuplicateGroup.Reason.REBROADCAST) shouldBe secondStage
                    // Every tuning and held-out pair is exactly one OTHER_CHANNEL group.
                    (TUNING + HELD_OUT)
                        .filter { pair ->
                            val group = groupOf[pair.first()]
                            group == null ||
                                group.reason != DuplicateGroup.Reason.OTHER_CHANNEL ||
                                group.programs.map { it.id }.toSet() != pair
                        }.shouldBeEmpty()
                    NEGATIVES.filter { (a, b) -> groupOf[a] != null && groupOf[a] === groupOf[b] }.shouldBeEmpty()
                    other.filter { group -> group.programs.distinctBy { it.channel }.size != group.programs.size }.shouldBeEmpty()
                    // The synthetic library: the marker changes no title's eligibility for either key, every group lies
                    // inside one copy, and each copy has exactly the real groups.
                    scaled
                        .filterIndexed { index, replica ->
                            val real = programs[index % programs.size]
                            (EpisodeTitle.key(replica.title) == null) != (EpisodeTitle.key(real.title) == null) ||
                                (OtherChannelMatcher.key(replica.title) == null) != (OtherChannelMatcher.key(real.title) == null)
                        }.map { it.id }
                        .shouldBeEmpty()
                    scaledGroups.filter { group -> group.programs.distinctBy { it.id / ID_OFFSET }.size != 1 }.shouldBeEmpty()
                    val realGroups = signature(groups, 0)
                    (0 until COPIES).filter { copy -> signature(scaledGroups, copy) != realGroups }.shouldBeEmpty()
                    counts(scaledGroups) shouldBe counts(groups).mapValues { it.value * COPIES }
                    // The committed fixture rows are verbatim copies of the data.
                    val fixtureJson = checkNotNull(javaClass.getResourceAsStream("/rebroadcast/issue-209-fixture.json"))
                    val fixture = RebroadcastData.programs(RebroadcastData.mapper.readTree(fixtureJson))
                    fixture
                        .filter { row -> byId[row.id]?.first()?.essentials() != row.essentials() }
                        .map { it.id }
                        .shouldBeEmpty()
                }
        }
    })

// まどか☆マギカ GR16 (2019) / BSBS01_0 「アニメA・放送開始10周年」 (2021): #1–#6 tuned the rules, #7–#11 are held out.
private val TUNING =
    listOf(
        setOf(6065L, 6056L),
        setOf(6079L, 6076L),
        setOf(6066L, 6057L),
        setOf(6067L, 6058L),
        setOf(6075L, 6059L),
        setOf(6068L, 6060L),
    )
private val HELD_OUT =
    listOf(
        setOf(6069L, 6061L),
        setOf(6070L, 6078L),
        setOf(6071L, 6062L),
        setOf(6072L, 6063L),
        setOf(6073L, 6064L),
    )

// The same negatives as issue-209-fixture.json "notTogether".
private val NEGATIVES =
    listOf(
        // TV Edition (re-edited) vs the series episode, within the duration rule.
        34505L to 6056L,
        34505L to 6065L,
        34506L to 6076L,
        34506L to 6079L,
        // Different episodes within the duration rule.
        6072L to 6064L,
        6074L to 6064L,
        // マギアレコード 2nd SEASON simultaneous on GR16/BSBS09_0: GR16 has no episode number.
        5393L to 5387L,
        // マリッジトキシン #01: one side alone has a subtitle and promotion (and they are 121.4 s apart).
        36554L to 39470L,
        // Dr.STONE 第4期 #13 vs SCIENCE FUTURE ＃13: different series wording.
        31650L to 31717L,
        // 新プロジェクトＸ simultaneous on GR24/GR25: no episode number.
        37621L to 37622L,
    )

private const val RATIO = DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO
private const val COPIES = 32
private const val ID_OFFSET = 1_000_000L

// About 11 years: more than the saved subset spans, so recordings of different copies never overlap on a channel.
private const val SHIFT_DAYS = 4_000L

/**
 * Copy [copy] of a real recording for the synthetic library: the id moves by [ID_OFFSET], the recording time by
 * [SHIFT_DAYS] (an exact shift, so durations and gaps inside the copy stay the same and no copy overlaps another), and
 * a bracketed `[cN]` goes in front of the program title (see [partitioned]). The marker keeps the comparison keys of
 * different copies apart (so no rebroadcast or other-channel group spans copies) without changing whether a title has
 * a key: bracketed text does not count as a program title, and frame-name stripping still applies.
 */
private fun replica(
    program: Program,
    copy: Int,
): Program {
    val dated = program.recordedAt != LocalDateTime.MIN
    return program.copy(
        id = program.id + copy * ID_OFFSET,
        title = partitioned(program.title, "[c$copy]"),
        recordedAt = if (dated) program.recordedAt.plusDays(copy * SHIFT_DAYS) else program.recordedAt,
    )
}

// Leading text that EpisodeTitle.key turns into nothing (whitespace and stripped broadcast markers such as "[新]"), and
// the separator it turns into one space after a frame name. Mirrors EpisodeTitle's marker set; keep the two in sync.
private val leadingNoise = Regex("""^(?:\s|\[[新再字終]])*""")
private val frameSeparator = Regex("""^(?:\s|\[[新再字終]])+""")

/**
 * [title] (NFKC) with [marker] inserted at the series-title boundary as EpisodeTitle/OtherChannelMatcher see it: after
 * the leading whitespace and stripped broadcast markers, and after a frame name with its separator if one follows.
 * Raw `startsWith` is not enough: "[新]アニメA・放送開始10周年　魔法少女…" (6056) would get the marker before the frame,
 * so the frame would no longer be stripped and the pair with GR16 6065 would vanish from every copy.
 */
private fun partitioned(
    title: String,
    marker: String,
): String {
    val text = Normalizer.normalize(title, Normalizer.Form.NFKC)
    val lead = leadingNoise.find(text)!!.value
    val body = text.substring(lead.length)
    val frame = OtherChannelMatcher.frameNames.firstOrNull { body.startsWith(it) }
    val separator = frame?.let { frameSeparator.find(body.substring(it.length))?.value }
    val boundary = lead.length + if (frame != null && separator != null) frame.length + separator.length else 0
    return text.substring(0, boundary) + "$marker " + text.substring(boundary)
}

// Days from the first recording start to the last recording end (durations capped at 1,000 days; unknown ones as 0).
private fun datedSpanDays(programs: List<Program>): Long {
    val dated = programs.filter { it.recordedAt != LocalDateTime.MIN }
    if (dated.isEmpty()) return 0
    val first = dated.minOf { it.recordedAt }
    val last =
        dated.maxOf { program ->
            val known = program.duration.isFinite() && program.duration > 0
            val seconds = if (known) program.duration.coerceAtMost(MAX_SPAN_SECONDS) else 0.0
            program.recordedAt.plusSeconds(seconds.toLong() + 1)
        }
    return ChronoUnit.DAYS.between(first, last) + 1
}

private const val MAX_SPAN_SECONDS = 86_400_000.0

private fun counts(groups: List<DuplicateGroup>): Map<DuplicateGroup.Reason, Int> = groups.groupingBy { it.reason }.eachCount()

// The groups of one copy, with ids moved back to the real ones.
private fun signature(
    groups: List<DuplicateGroup>,
    copy: Int,
): Set<Pair<DuplicateGroup.Reason, Set<Long>>> =
    groups
        .filter { group -> group.programs.first().id / ID_OFFSET == copy.toLong() }
        .map { group -> group.reason to group.programs.map { it.id - copy * ID_OFFSET }.toSet() }
        .toSet()

private fun Program.essentials() = listOf(id, channel, recordedAt, duration, title)

private fun median(
    runs: Int = 5,
    block: () -> Unit,
): Duration = List(runs) { measureTime(block) }.sorted()[runs / 2]
