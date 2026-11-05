package me.pinfort.tsvideos.library.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import java.io.File
import java.time.format.DateTimeFormatter
import kotlin.time.measureTimedValue

/**
 * Optional, read-only validation of the production detector on saved `tvmcli search --json` output (#208).
 *
 * Disabled unless TSVIDEOS_REBROADCAST_DATA names a directory with `search-*.json` and `synduality-pairs.csv`. Only
 * those files are read; nothing contacts a database or NAS. The fixture regression (RebroadcastFixtureTest) runs
 * regardless. Run from backend/ (the variable is a test input, so changing it re-runs the test; add --rerun after
 * editing the files themselves):
 *
 *   $env:TSVIDEOS_REBROADCAST_DATA = "E:/Users/pinfo/Documents/tv/manager"
 *   ./gradlew library:test --tests "me.pinfort.tsvideos.library.api.RebroadcastDataValidationTest"
 *
 * report.md (all groups, additional candidates, bucket sizes, elapsed time) and fixture-candidate.json (only
 * id/title/channel/recordedAt/duration of the known pairs and negatives) are written to TSVIDEOS_REBROADCAST_REPORT,
 * default library/build/reports/rebroadcast-validation/. A saved search subset is not the whole library: passing shows
 * that the known pairs and negatives behave as specified on that subset, not production precision.
 */
class RebroadcastDataValidationTest :
    ExpectSpec({
        val dataDir = System.getenv("TSVIDEOS_REBROADCAST_DATA")?.takeIf { it.isNotBlank() }?.let(::File)

        context("external #208 data (TSVIDEOS_REBROADCAST_DATA)") {
            expect("known pairs, negatives, disjoint membership and an unchanged first stage")
                .config(enabled = dataDir != null) {
                    val dir = checkNotNull(dataDir)
                    val files =
                        dir
                            .listFiles { file -> file.isFile && file.name.startsWith("search-") && file.name.endsWith(".json") }
                            .orEmpty()
                            .sorted()
                    check(files.isNotEmpty()) { "no search-*.json in $dir" }

                    // Searches overlap; the same program id is one recording, never a duplicate of itself.
                    val rows = files.flatMap { file -> RebroadcastData.programs(RebroadcastData.readTree(file)) }
                    val byId = rows.groupBy { it.id }
                    byId.filterValues { copies -> copies.distinct().size > 1 }.keys.shouldBeEmpty()
                    val programs = byId.values.map { it.first() }.sortedBy { it.id }

                    val (groups, elapsed) = measureTimedValue { RebroadcastData.detect(programs) }
                    val baseline = RebroadcastData.baseline(programs)
                    val known =
                        listOf(setOf(19029L, 19030L), setOf(5325L, 5327L), setOf(5332L, 5328L)) +
                            syndualityPairs(File(dir, "synduality-pairs.csv"))
                    val groupOf = groups.flatMap { group -> group.programs.map { it.id to group } }.toMap()
                    val buckets =
                        programs
                            .mapNotNull { program -> RebroadcastMatcher.key(program.title)?.let { (program.channel to it) to program } }
                            .groupBy({ it.first }, { it.second })

                    val outputDir =
                        System.getenv("TSVIDEOS_REBROADCAST_REPORT")?.takeIf { it.isNotBlank() }?.let(::File)
                            ?: File("build/reports/rebroadcast-validation")
                    outputDir.mkdirs()
                    val report =
                        report(
                            files = files,
                            rowCount = rows.size,
                            programs = programs,
                            baseline = baseline,
                            groups = groups,
                            known = known,
                            maxBucket = buckets.values.maxOfOrNull { it.size } ?: 0,
                            elapsedMillis = elapsed.inWholeMilliseconds,
                        )
                    File(outputDir, "report.md").writeText(report)
                    File(outputDir, "fixture-candidate.json").writeText(fixtureCandidate(byId, known, NEGATIVE_PAIRS))
                    println(report)

                    RebroadcastData.sharedMembers(groups).shouldBeEmpty()
                    // OTHER_CHANNEL (#209, the third stage) is validated by OtherChannelDataValidationTest.
                    shape(groups.filter { it.reason in FIRST_STAGE }) shouldBe shape(baseline)
                    known.flatten().filter { it !in byId }.shouldBeEmpty()
                    // A negative that is absent from the searches would pass vacuously, so every one must be present.
                    NEGATIVE_PAIRS.flatMap { listOf(it.first, it.second) }.filter { it !in byId }.shouldBeEmpty()
                    // Each known pair is exactly one REBROADCAST group: no extra member, not split, not a baseline group.
                    val notExact =
                        known.filter { pair ->
                            val group = groupOf[pair.first()]
                            group == null || group.reason != DuplicateGroup.Reason.REBROADCAST || group.ids() != pair
                        }
                    notExact.shouldBeEmpty()
                    NEGATIVE_PAIRS.filter { (a, b) -> groupOf[a] != null && groupOf[a] === groupOf[b] }.shouldBeEmpty()
                    groups
                        .filter { it.reason == DuplicateGroup.Reason.REBROADCAST }
                        .flatMap { it.programs }
                        .filter { EXCLUDED_TITLE.containsMatchIn(it.title) || RebroadcastMatcher.key(it.title) == null }
                        .map { it.id }
                        .shouldBeEmpty()
                }
        }
    })

// Negatives from the #208 investigation, all present in the saved searches (the same pairs as issue-208-fixture.json).
private val NEGATIVE_PAIRS =
    listOf(
        // マリッジトキシン #01 on different channels.
        36554L to 39470L,
        // オーバーテイク！ Rd.08 with the subtitle quoted differently.
        20971L to 22479L,
        // 薬屋のひとりごと FRIDAY ANIME NIGHT: no episode number.
        39386L to 39388L,
        39390L to 39392L,
        // BanG Dream! It's MyGO!!!!! specials (pre-broadcast, digest, look-back Part1/Part2) and #1.
        19028L to 19029L,
        19848L to 19028L,
        19850L to 19851L,
        // Dr.STONE第4期 #7,8: a two-episode broadcast repeated three times.
        39149L to 39150L,
        39150L to 39151L,
        39149L to 39151L,
        // 劇場版 魔法少女まどか☆マギカ ［前編］: a film, no episode number.
        37944L to 39413L,
    )

private val FIRST_STAGE = setOf(DuplicateGroup.Reason.SAME_BROADCAST, DuplicateGroup.Reason.OVERLAP)

// Specials, digests, films, binge broadcasts and multi-episode lists must never become rebroadcast candidates.
private val EXCLUDED_TITLE = Regex("""特番|ダイジェスト|劇場版|一挙|#\d+\s*[,、・]""")

// The "Ids" cell of synduality-pairs.csv, e.g. "20266/20278".
private val ID_PAIR = Regex("""(\d+)\s*/\s*(\d+)""")

private fun DuplicateGroup.ids(): Set<Long> = programs.map { it.id }.toSet()

private fun shape(groups: List<DuplicateGroup>) = groups.map { group -> group.reason to group.programs.map { it.id } }

/**
 * The 12 SYNDUALITY pairs, one per CSV data row ("Episode","Ids","DeltaSeconds","Ratio"; Ids is "a/b"). Every row
 * after the header must carry exactly one id pair; whether the ids are in the searches is checked by the caller.
 */
private fun syndualityPairs(csv: File): List<Set<Long>> {
    check(csv.isFile) { "${csv.name} not found; the 12 SYNDUALITY pairs cannot be checked" }
    val lines = csv.readLines().filter { it.isNotBlank() }
    check(lines.firstOrNull()?.contains("Ids") == true) { "${csv.name}: missing the \"Ids\" header" }
    val pairs =
        lines.drop(1).map { line ->
            val match = ID_PAIR.findAll(line).singleOrNull() ?: error("${csv.name}: expected one \"a/b\" id pair in \"$line\"")
            val (a, b) = match.destructured
            setOf(a.toLong(), b.toLong()).also { check(it.size == 2) { "${csv.name}: \"$line\" pairs an id with itself" } }
        }
    check(pairs.size == 12) { "${csv.name}: expected 12 pairs, found ${pairs.size}" }
    return pairs
}

private fun report(
    files: List<File>,
    rowCount: Int,
    programs: List<Program>,
    baseline: List<DuplicateGroup>,
    groups: List<DuplicateGroup>,
    known: List<Set<Long>>,
    maxBucket: Int,
    elapsedMillis: Long,
): String =
    buildString {
        appendLine("# #208 rebroadcast validation (saved search subset, not the whole library)")
        appendLine()
        appendLine("- files: ${files.joinToString { it.name }}")
        appendLine("- rows: $rowCount, unique program ids: ${programs.size}")
        appendLine("- programs with a comparison key: ${programs.count { RebroadcastMatcher.key(it.title) != null }}")
        appendLine("- largest channel+key bucket: $maxBucket")
        appendLine("- detect() elapsed: $elapsedMillis ms")
        appendLine("- baseline (pre-#208) groups: ${baseline.size} ${baseline.groupingBy { it.reason }.eachCount()}")
        appendLine("- new groups: ${groups.size} ${groups.groupingBy { it.reason }.eachCount()}")
        appendLine()
        appendLine("| kind | reason | ids | channel | comparison title | recordedAt | durations | diff s | ratio |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        groups.forEach { group ->
            val kind =
                when {
                    group.reason == DuplicateGroup.Reason.OTHER_CHANNEL -> "other-channel (#209)"
                    group.reason != DuplicateGroup.Reason.REBROADCAST -> "baseline"
                    group.ids() in known -> "known"
                    else -> "ADDITIONAL"
                }
            val durations = group.programs.map { it.duration }
            val cells =
                listOf(
                    kind,
                    group.reason.name,
                    group.programs.joinToString(",") { it.id.toString() },
                    group.programs.first().channel,
                    group.comparisonTitle.orEmpty(),
                    group.programs.joinToString(" / ") { it.recordedAt.toString() },
                    durations.joinToString(" / "),
                    "%.3f".format(durations.max() - durations.min()),
                    if (durations.max() > 0) "%.5f".format(durations.min() / durations.max()) else "-",
                )
            appendLine(cells.joinToString(" | ", "| ", " |"))
        }
        appendLine()
        appendLine("Titles of ADDITIONAL candidates (a person must review them; being grouped is not a precision check):")
        groups
            .filter { it.reason == DuplicateGroup.Reason.REBROADCAST && it.ids() !in known }
            .forEach { group -> group.programs.forEach { appendLine("- ${it.id} ${it.channel} ${it.recordedAt}: ${it.title}") } }
    }

/** Only the fields the detector reads; no file paths, program names or sizes. */
private fun fixtureCandidate(
    byId: Map<Long, List<Program>>,
    known: List<Set<Long>>,
    negatives: List<Pair<Long, Long>>,
): String {
    val ids = (known.flatten() + negatives.flatMap { listOf(it.first, it.second) }).distinct()
    val rows =
        ids
            .mapNotNull { byId[it]?.first() }
            .map { program ->
                linkedMapOf(
                    "id" to program.id,
                    "channel" to program.channel,
                    "recordedAt" to program.recordedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    "duration" to program.duration,
                    "title" to program.title,
                    "source" to "actual",
                    "copied" to listOf("id", "channel", "recordedAt", "duration", "title"),
                )
            }
    val writer = RebroadcastData.mapper.writer().withDefaultPrettyPrinter()
    return writer.writeValueAsString(
        linkedMapOf(
            "schemaVersion" to 1,
            "note" to "Copied from saved tvmcli search --json output by RebroadcastDataValidationTest. Review before committing.",
            "programs" to rows,
            "expectedRebroadcastGroups" to known.map { it.sorted() },
            "notTogether" to negatives.map { listOf(it.first, it.second) },
        ),
    )
}
