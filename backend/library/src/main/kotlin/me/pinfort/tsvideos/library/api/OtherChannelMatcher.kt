package me.pinfort.tsvideos.library.api

import me.pinfort.tsvideos.catalog.domain.Program
import java.text.Normalizer
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Other-channel, single-episode candidates (#209): the same comparison title and episode recorded on different
 * channels. Pure: no I/O. Simultaneous broadcasts are included, so unlike [RebroadcastMatcher] no time condition applies.
 *
 * The key is [EpisodeTitle]'s plus only the frame-name correction below, both observed in the #209 data; no fuzzy or
 * partial matching, so differing or omitted subtitles, seasons and editions stay apart (see backend/docs/duplicate-review.md).
 */
internal object OtherChannelMatcher {
    /**
     * Trial values from the #209 sample: 11 title-matched cross-channel pairs within 0.03–1.84 s (ratio ≥ 0.9989), and
     * 13 cross-channel pairs of one broadcast slot that the title rules cannot match within 0.02–3.51 s. Same-title
     * pairs 58.8–59.8 s apart (新プロジェクトＸ; another edit or recording length) stay apart. マリッジトキシン #01
     * (121.4 s apart) is no same-title pair: one side alone has a subtitle and promotion, so its comparison titles
     * already differ. Not a verified production accuracy.
     */
    const val MAX_DURATION_DIFFERENCE_SECONDS = 5.0
    const val MIN_DURATION_RATIO = 0.995

    /**
     * Frame (programming slot) names that one channel puts in front of an otherwise unchanged series title, as observed
     * in the #209 data (BSBS01_0 "アニメA・放送開始10周年 魔法少女まどか☆マギカ #N" vs GR16 "魔法少女まどか☆マギカ#N").
     * A name is added here only after real recordings confirm it; it must be followed by a space and a remaining title.
     */
    val frameNames: List<String> = listOf("アニメA・放送開始10周年").map { Normalizer.normalize(it, Normalizer.Form.NFKC) }

    /** The cross-channel comparison key, or null when the title is not a single clearly numbered episode. */
    fun key(title: String): EpisodeTitle.Key? {
        val key = EpisodeTitle.key(title) ?: return null
        val frame = frameNames.firstOrNull { key.title.startsWith("$it ") } ?: return key
        val rest = key.title.removePrefix("$frame ")
        // The frame name alone ("アニメA・放送開始10周年 #1") does not name a program.
        return if (EpisodeTitle.hasProgramTitle(rest.substringBefore(EpisodeTitle.EPISODE))) key.copy(title = rest) else null
    }

    /**
     * Groups [programs] (already outside every same-broadcast, overlap and rebroadcast group) into other-channel
     * candidates. Within one key bucket, compatible pairs form components and a component is kept only when every pair
     * is compatible; since two recordings of one channel are never compatible here, a component with a repeated
     * channel is left out entirely, as is a chain A-B, B-C without A-C.
     */
    fun groups(programs: List<Program>): List<Pair<EpisodeTitle.Key, List<Program>>> =
        programs
            .filter { it.recordedAt != LocalDateTime.MIN && it.channel.isNotEmpty() && it.duration.isFinite() && it.duration > 0 }
            .mapNotNull { program -> key(program.title)?.let { it to program } }
            .groupBy({ it.first }, { it.second })
            .flatMap { (key, members) ->
                EpisodeTitle
                    .cliques(members.sortedWith(compareBy<Program> { it.recordedAt }.thenBy { it.id }), ::compatible)
                    .map { key to it }
            }

    /** Recordings on different channels (at any time, also simultaneous) whose durations agree within both thresholds. */
    fun compatible(
        a: Program,
        b: Program,
    ): Boolean {
        if (a.channel == b.channel) return false
        val longer = maxOf(a.duration, b.duration)
        val shorter = minOf(a.duration, b.duration)
        return abs(a.duration - b.duration) <= MAX_DURATION_DIFFERENCE_SECONDS && shorter / longer >= MIN_DURATION_RATIO
    }
}
