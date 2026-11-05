package me.pinfort.tsvideos.library.api

import me.pinfort.tsvideos.catalog.api.ProgramInventory
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import me.pinfort.tsvideos.library.domain.DuplicateKeepCriterion
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
class DuplicateProgramCommand(
    private val programs: ProgramInventory,
) {
    companion object {
        // 0.5 以下だと、長時間の録画とそれに含まれる別々の番組がまとめてひとつのグループになりうる
        const val DEFAULT_MIN_OVERLAP_RATIO = 0.8

        // drops が少ない → COMPLETED → 長い → 大きい → 古い id の順に残す。
        // keepOrder とレビュー画面の「決め手」はどちらもこの一覧から作るので、順序を変えるときはここだけ直す
        val keepCriteria: List<Pair<DuplicateKeepCriterion, Comparator<Program>>> =
            listOf(
                DuplicateKeepCriterion.DROPS to compareBy { if (it.drops < 0) Int.MAX_VALUE else it.drops },
                DuplicateKeepCriterion.STATUS to
                    compareBy {
                        when (it.status) {
                            Program.Status.COMPLETED -> 0
                            Program.Status.REGISTERED -> 1
                            Program.Status.ERROR -> 2
                        }
                    },
                DuplicateKeepCriterion.DURATION to compareByDescending { it.duration },
                DuplicateKeepCriterion.SIZE to compareByDescending { it.size },
                DuplicateKeepCriterion.ID to compareBy { it.id },
            )

        private val keepOrder: Comparator<Program> = keepCriteria.map { it.second }.reduce { a, b -> a.then(b) }

        /** [winner] が [runnerUp] より先に並んだ最初の基準。id は一意なので通常は ID までに必ず決まる */
        fun decisiveCriterion(
            winner: Program,
            runnerUp: Program,
        ): DuplicateKeepCriterion =
            keepCriteria.firstOrNull { (_, order) -> order.compare(winner, runnerUp) != 0 }?.first
                ?: DuplicateKeepCriterion.ID
    }

    // 全件をメモリに載せて判定する。program が数万件規模である前提
    // minOverlapRatio は同一放送・重複の判定だけに使う。再放送候補 (RebroadcastMatcher)・別局候補 (OtherChannelMatcher) の
    // 長さの条件はそれぞれ別の固定値
    fun detect(minOverlapRatio: Double = DEFAULT_MIN_OVERLAP_RATIO): List<DuplicateGroup> =
        group(programs.recordedPrograms(), minOverlapRatio)

    /**
     * [detect] の結果のうち [channel] の同一放送・重複・再放送候補のグループだけ。これらはチャンネル内でしか作られず、
     * 別局候補 (第3段階) はその後に残りから作られるので、そのチャンネルの録画だけから判定しても [detect] の同じチャンネルの
     * これらのグループと完全に一致する。別局候補は複数チャンネルにまたがるので、ここでは返さない (判定には [detect] を使う)。
     * 番組名などで絞らないのは、重なりによるまとまりや再放送候補からの除外がタイトルをまたいで決まるため
     */
    internal fun detectOnChannel(
        channel: String,
        minOverlapRatio: Double = DEFAULT_MIN_OVERLAP_RATIO,
    ): List<DuplicateGroup> =
        group(programs.recordedProgramsOnChannel(channel), minOverlapRatio)
            .filter { it.reason != DuplicateGroup.Reason.OTHER_CHANNEL }

    /**
     * 同一チャンネルで、録画時間帯の重なりが長い方の録画の [minOverlapRatio] 以上ある番組同士をまとめる。
     * 長い方を基準にするので、連続番組のマージン録画による僅かな重なりや、
     * 長時間の録画がその時間帯に含まれる別の番組を覆っているだけのものは重複とみなさない。
     * 開始時刻が同じで長さが不明なものは、開始時刻の一致だけで重複とみなす。
     */
    internal fun group(
        programs: List<Program>,
        minOverlapRatio: Double,
    ): List<DuplicateGroup> {
        val targets = programs.filter { it.recordedAt != LocalDateTime.MIN && it.channel.isNotEmpty() }
        val parents = IntArray(targets.size) { it }

        fun find(i: Int): Int {
            var root = i
            while (parents[root] != root) root = parents[root]
            parents[i] = root
            return root
        }

        fun union(
            a: Int,
            b: Int,
        ) {
            parents[find(a)] = find(b)
        }

        targets.indices
            .groupBy { targets[it].channel }
            .values
            .forEach { indices ->
                val sorted = indices.sortedBy { targets[it].recordedAt }
                for (i in sorted.indices) {
                    val a = targets[sorted[i]]
                    for (j in i + 1 until sorted.size) {
                        val b = targets[sorted[j]]
                        if (b.recordedAt == a.recordedAt) {
                            if (a.duration <= 0 || b.duration <= 0 || overlapRatio(a, b) >= minOverlapRatio) {
                                union(sorted[i], sorted[j])
                            }
                            continue
                        }
                        // 開始時刻順に並んでいるので、a の終了以降に始まる番組以降は重ならない
                        if (a.duration <= 0 || b.startSeconds() >= a.endSeconds()) break
                        if (b.duration <= 0) continue

                        if (overlapRatio(a, b) >= minOverlapRatio) {
                            union(sorted[i], sorted[j])
                        }
                    }
                }
            }

        val broadcasts =
            targets.indices
                .groupBy { find(it) }
                .values
                .filter { it.size > 1 }
                .map { indices ->
                    val members = indices.map { targets[it] }
                    val reason =
                        if (members.all { it.recordedAt == members.first().recordedAt }) {
                            DuplicateGroup.Reason.SAME_BROADCAST
                        } else {
                            DuplicateGroup.Reason.OVERLAP
                        }
                    DuplicateGroup(reason, members.sortedWith(keepOrder))
                }.sortedBy { group -> group.programs.minOf { it.recordedAt } }

        // Second stage (#208): the groups above are final; their members never join a rebroadcast group, so every
        // program belongs to at most one group. A same-broadcast pair A/B with a rebroadcast C therefore yields A/B only.
        val grouped = broadcasts.flatMap { group -> group.programs.map { it.id } }.toSet()
        val rebroadcasts =
            RebroadcastMatcher
                .groups(targets.filter { it.id !in grouped })
                .map { (key, members) -> DuplicateGroup(DuplicateGroup.Reason.REBROADCAST, members.sortedWith(keepOrder), key.display) }
                .sortedWith(
                    compareBy<DuplicateGroup> { group -> group.programs.minOf { it.recordedAt } }
                        .thenBy { group -> group.programs.minOf { it.id } },
                )
        // Third stage (#209): only what is still ungrouped can become an other-channel candidate, so the first two stages
        // are unchanged and groups stay disjoint. Same-broadcast A/B with C on another channel therefore yields A/B only.
        val rebroadcast = rebroadcasts.flatMap { group -> group.programs.map { it.id } }.toSet()
        val otherChannels =
            OtherChannelMatcher
                .groups(targets.filter { it.id !in grouped && it.id !in rebroadcast })
                .map { (key, members) -> DuplicateGroup(DuplicateGroup.Reason.OTHER_CHANNEL, members.sortedWith(keepOrder), key.display) }
                .sortedWith(
                    compareBy<DuplicateGroup> { group -> group.programs.minOf { it.recordedAt } }
                        .thenBy { group -> group.programs.minOf { it.id } },
                )
        // Stable sort: earlier stages' groups keep their previous relative order.
        return (broadcasts + rebroadcasts + otherChannels).sortedBy { group -> group.programs.minOf { it.recordedAt } }
    }

    // b が a 以降に始まる前提で、重なりの長さを長い方の録画の長さで割る
    private fun overlapRatio(
        a: Program,
        b: Program,
    ): Double = (minOf(a.endSeconds(), b.endSeconds()) - b.startSeconds()) / maxOf(a.duration, b.duration)

    private fun Program.startSeconds(): Double = recordedAt.toEpochSecond(ZoneOffset.UTC).toDouble()

    private fun Program.endSeconds(): Double = startSeconds() + duration
}
