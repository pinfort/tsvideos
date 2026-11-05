package me.pinfort.tsvideos.library.api

import me.pinfort.tsvideos.catalog.domain.Program
import java.text.Normalizer

/**
 * The comparison key shared by the rebroadcast (#208) and other-channel (#209) candidates: the title with exactly one
 * clearly numbered episode, normalized for comparison only. Pure: no I/O. The program's title is never changed.
 *
 * Moved unchanged from RebroadcastMatcher; channel-specific corrections (such as OtherChannelMatcher's frame names)
 * are applied by each matcher on top of this key, never here, so the rebroadcast rules stay exactly as in #208.
 */
internal object EpisodeTitle {
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
    const val EPISODE = "\u0000"

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
        if (!hasProgramTitle(before)) return null
        val rest = (before.trimEnd() + " " + EPISODE + " " + following.trimStart()).trim()
        return Key(rest, number)
    }

    /** Whether [text] (the part of a key in front of the episode) still names a program once bracketed fields are dropped. */
    fun hasProgramTitle(text: String): Boolean = text.replace(bracketed, "").any { it.isLetterOrDigit() }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /**
     * Splits [bucket] (programs sharing one key, in a fixed order) into candidate groups: [compatible] pairs form
     * connected components, and a component is kept only when every pair in it is compatible. Otherwise the whole
     * component is left out, so A-B and B-C without A-C never chain, and nothing depends on the input order.
     */
    fun cliques(
        bucket: List<Program>,
        compatible: (Program, Program) -> Boolean,
    ): List<List<Program>> {
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
}
