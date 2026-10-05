package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.DuplicateGroup
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
class DuplicateProgramCommand(
    private val programMapper: ProgramMapper,
) {
    companion object {
        // 0.5 以下だと、長時間の録画とそれに含まれる別々の番組がまとめてひとつのグループになりうる
        const val DEFAULT_MIN_OVERLAP_RATIO = 0.8

        // drops が少ない → COMPLETED → 長い → 大きい → 古い id の順に残す
        private val keepOrder: Comparator<Program> =
            compareBy<Program> { if (it.drops < 0) Int.MAX_VALUE else it.drops }
                .thenBy {
                    when (it.status) {
                        Program.Status.COMPLETED -> 0
                        Program.Status.REGISTERED -> 1
                        Program.Status.ERROR -> 2
                    }
                }.thenByDescending { it.duration }
                .thenByDescending { it.size }
                .thenBy { it.id }
    }

    // 全件をメモリに載せて判定する。program が数万件規模である前提
    fun detect(minOverlapRatio: Double = DEFAULT_MIN_OVERLAP_RATIO): List<DuplicateGroup> =
        group(programMapper.selectAll().map { it.toDomain() }, minOverlapRatio)

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

        return targets.indices
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
    }

    // b が a 以降に始まる前提で、重なりの長さを長い方の録画の長さで割る
    private fun overlapRatio(
        a: Program,
        b: Program,
    ): Double = (minOf(a.endSeconds(), b.endSeconds()) - b.startSeconds()) / maxOf(a.duration, b.duration)

    private fun Program.startSeconds(): Double = recordedAt.toEpochSecond(ZoneOffset.UTC).toDouble()

    private fun Program.endSeconds(): Double = startSeconds() + duration
}
