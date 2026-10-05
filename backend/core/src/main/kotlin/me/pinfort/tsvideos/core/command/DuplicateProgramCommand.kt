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
        const val DEFAULT_MIN_OVERLAP_RATIO = 0.5

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

    fun detect(minOverlapRatio: Double = DEFAULT_MIN_OVERLAP_RATIO): List<DuplicateGroup> =
        group(programMapper.selectAll().map { it.toDomain() }, minOverlapRatio)

    /**
     * 同一チャンネルで開始時刻が同じ、または録画時間帯の重なりが短い方の録画の [minOverlapRatio] 以上ある番組同士をまとめる。
     * 連続番組のマージン録画による僅かな重なりは重複とみなさない。
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
                            union(sorted[i], sorted[j])
                            continue
                        }
                        // 開始時刻順に並んでいるので、a の終了以降に始まる番組以降は重ならない
                        if (a.duration <= 0 || b.startSeconds() >= a.endSeconds()) break
                        if (b.duration <= 0) continue

                        val overlap = minOf(a.endSeconds(), b.endSeconds()) - b.startSeconds()
                        if (overlap / minOf(a.duration, b.duration) >= minOverlapRatio) {
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

    private fun Program.startSeconds(): Double = recordedAt.toEpochSecond(ZoneOffset.UTC).toDouble()

    private fun Program.endSeconds(): Double = startSeconds() + duration
}
