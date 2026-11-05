package me.pinfort.tsvideos.library.api

import me.pinfort.tsvideos.catalog.domain.Program
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Same-channel, single-episode rebroadcast candidates (#208). Pure: no I/O, so the rules are tested on their own.
 *
 * Only the comparison key is normalized; the program's title is never changed. The rules are deliberately narrow and
 * every ambiguity rejects the recording rather than guessing (see backend/docs/duplicate-review.md).
 */
internal object RebroadcastMatcher {
    /** Trial values from the #208 sample (15 same-channel pairs within 0.02–3.51 s); not a verified production accuracy. */
    const val MAX_DURATION_DIFFERENCE_SECONDS = 5.0
    const val MIN_DURATION_RATIO = 0.995

    /**
     * Broadcast attribute markers stripped from the comparison key: only those that say nothing about the content of
     * the episode (new/rebroadcast/captioned/final). Everything else is kept, because it can mark a different edition:
     * [吹]/[二]/[多] dubbed or bilingual audio, [解] audio description, [前]/[後] 前編/後編, [デ]/[無]/[料] etc.
     */
    private val markers = setOf("新", "再", "字", "終")
    private val marker = Regex("""\[(${markers.joinToString("|")})]""")
    private val terminalMarker = Regex("""\s*\[(${markers.joinToString("|")})]\s*$""")
    private val bracketed = Regex("""\[[^\[\]]*]""")

    // Recorder management fields appended to the end of the title, e.g. "[ID:10194][SV:KT].m2ts" or "[ID:NULL]".
    private val terminalField = Regex("""\s*\[(?:ID|SV|SERVER):[^\[\]]*]\s*$""", RegexOption.IGNORE_CASE)
    private val terminalExtension = Regex("""\s*\.m2ts\s*$""", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("""\s+""")

    // Anything that looks like an episode number, including ranges and lists, so that e.g. "#7,8" or "第1〜12話" is
    // seen as an episode token and then rejected instead of being read as "#7".
    private const val SEPARATORS = """,、・~〜～\-－–—&+＆/"""
    private const val NUMERALS = """\d〇一二三四五六七八九十百千"""
    private val episodeLike =
        Regex(
            """#\s*\d+(?:\.\d+)?(?:\s*[$SEPARATORS]\s*(?:#|第)?\s*\d+)*""" +
                """|第\s*[$NUMERALS]+(?:\s*[$SEPARATORS]\s*(?:第)?\s*[$NUMERALS]+)*\s*話""",
        )
    private val singleEpisode = Regex("""^(?:#\s*(\d+)|第\s*(\d+)\s*話)$""")

    // A second episode joined to the token, written so that the token itself still looks single: "第1話〜3話",
    // "#1と2", "1・#2". A kanji numeral only counts when 話 follows, so a subtitle such as "#1〜二人の約束〜" is kept.
    private const val CONNECTORS = """[$SEPARATORS]|と|及び|および|から"""
    private val continuationAfter = Regex("""^\s*(?:$CONNECTORS)\s*(?:#|第)?\s*(?:\d|[〇一二三四五六七八九十百千]+\s*話)""")
    private val continuationBefore = Regex("""\d\s*(?:$CONNECTORS)\s*$""")

    // A placeholder that cannot occur in NFKC text with whitespace collapsed.
    private const val EPISODE = "\u0000"

    /** The comparison key of a title with exactly one clearly numbered episode, or null when it is not a candidate. */
    data class Key(
        val title: String,
        val episode: Int,
    ) {
        /** The key as shown to operators: the episode token rewritten as `#N`. */
        val display: String get() = title.replace(EPISODE, "#$episode")
    }

    fun key(title: String): Key? {
        var text = Normalizer.normalize(title, Normalizer.Form.NFKC).trim()
        while (true) {
            val stripped = text.replace(terminalExtension, "").replace(terminalField, "").replace(terminalMarker, "")
            if (stripped == text) break
            text = stripped
        }
        text = text.replace(marker, " ").replace(whitespace, " ").trim()
        if (text.isEmpty() || "一挙" in text) return null
        val tokens = episodeLike.findAll(text).toList()
        val token = tokens.singleOrNull() ?: return null
        val match = singleEpisode.matchEntire(token.value) ?: return null
        val number = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull() ?: return null
        // "#12a", "#1." or a token glued to letters/digits ("2#3") is not a single clear episode.
        if (text.getOrNull(token.range.first - 1)?.isAsciiLetterOrDigit() == true) return null
        val after = text.getOrNull(token.range.last + 1)
        if (after != null && (after.isAsciiLetterOrDigit() || after == '.')) return null
        val before = text.substring(0, token.range.first)
        val following = text.substring(token.range.last + 1)
        if (continuationAfter.containsMatchIn(following) || continuationBefore.containsMatchIn(before)) return null
        // The program title itself must remain: "#1" or "[再]第1話[ID:1].m2ts" alone would match any untitled recording.
        if (before.replace(bracketed, "").none { it.isLetterOrDigit() }) return null
        val rest = (before.trimEnd() + " " + EPISODE + " " + following.trimStart()).trim()
        return Key(rest, number)
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /**
     * Groups [programs] (already outside every same-broadcast/overlap group) into rebroadcast candidates. Within one
     * channel + key bucket, compatible pairs form components; a component is kept only when every pair in it is
     * compatible, otherwise the whole component is left out (no chaining, no order-dependent splitting).
     */
    fun groups(programs: List<Program>): List<Pair<Key, List<Program>>> =
        programs
            .filter { it.recordedAt != LocalDateTime.MIN && it.channel.isNotEmpty() && it.duration.isFinite() && it.duration > 0 }
            .mapNotNull { program -> key(program.title)?.let { (program.channel to it) to program } }
            .groupBy({ it.first }, { it.second })
            .flatMap { (bucket, members) ->
                cliques(members.sortedWith(compareBy<Program> { it.recordedAt }.thenBy { it.id })).map { bucket.second to it }
            }

    private fun cliques(bucket: List<Program>): List<List<Program>> {
        if (bucket.size < 2) return emptyList()
        val parents = IntArray(bucket.size) { it }

        fun find(i: Int): Int {
            var root = i
            while (parents[root] != root) root = parents[root]
            parents[i] = root
            return root
        }
        for (i in bucket.indices) {
            for (j in i + 1 until bucket.size) {
                if (compatible(bucket[i], bucket[j])) parents[find(i)] = find(j)
            }
        }
        return bucket.indices
            .groupBy { find(it) }
            .values
            .filter { it.size > 1 }
            .map { indices -> indices.map { bucket[it] } }
            .filter { members -> members.indices.all { i -> (i + 1 until members.size).all { j -> compatible(members[i], members[j]) } } }
    }

    /** Different, non-overlapping recordings whose durations agree within both thresholds. */
    fun compatible(
        a: Program,
        b: Program,
    ): Boolean {
        if (a.recordedAt == b.recordedAt) return false
        val (first, second) = if (a.recordedAt < b.recordedAt) a to b else b to a
        if (start(second) < start(first) + first.duration) return false
        val longer = maxOf(a.duration, b.duration)
        val shorter = minOf(a.duration, b.duration)
        return abs(a.duration - b.duration) <= MAX_DURATION_DIFFERENCE_SECONDS && shorter / longer >= MIN_DURATION_RATIO
    }

    private fun start(program: Program): Double = program.recordedAt.toEpochSecond(ZoneOffset.UTC).toDouble()
}
