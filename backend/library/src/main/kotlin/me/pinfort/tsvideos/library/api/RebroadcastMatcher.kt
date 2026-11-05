package me.pinfort.tsvideos.library.api

import me.pinfort.tsvideos.catalog.domain.Program
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Same-channel, single-episode rebroadcast candidates (#208). Pure: no I/O, so the rules are tested on their own.
 *
 * Only the comparison key ([EpisodeTitle]) is normalized; the program's title is never changed. The rules are
 * deliberately narrow and every ambiguity rejects the recording rather than guessing (see backend/docs/duplicate-review.md).
 */
internal object RebroadcastMatcher {
    /** Trial values from the #208 sample (15 same-channel pairs within 0.02–3.51 s); not a verified production accuracy. */
    const val MAX_DURATION_DIFFERENCE_SECONDS = 5.0
    const val MIN_DURATION_RATIO = 0.995

    /** The comparison key of a title with exactly one clearly numbered episode, or null when it is not a candidate. */
    fun key(title: String): EpisodeTitle.Key? = EpisodeTitle.key(title)

    /**
     * Groups [programs] (already outside every same-broadcast/overlap group) into rebroadcast candidates. Within one
     * channel + key bucket, compatible pairs form components; a component is kept only when every pair in it is
     * compatible, otherwise the whole component is left out (no chaining, no order-dependent splitting).
     */
    fun groups(programs: List<Program>): List<Pair<EpisodeTitle.Key, List<Program>>> =
        programs
            .filter { it.recordedAt != LocalDateTime.MIN && it.channel.isNotEmpty() && it.duration.isFinite() && it.duration > 0 }
            .mapNotNull { program -> key(program.title)?.let { (program.channel to it) to program } }
            .groupBy({ it.first }, { it.second })
            .flatMap { (bucket, members) ->
                EpisodeTitle
                    .cliques(members.sortedWith(compareBy<Program> { it.recordedAt }.thenBy { it.id }), ::compatible)
                    .map { bucket.second to it }
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
