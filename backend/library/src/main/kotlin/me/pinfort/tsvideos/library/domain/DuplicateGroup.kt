package me.pinfort.tsvideos.library.domain

import me.pinfort.tsvideos.catalog.domain.Program

/**
 * 同じ放送、または同じ回の再放送を録画したと判定された番組のまとまり
 *
 * @param programs 残すべき順に並んだ番組。先頭が残す候補
 * @param comparisonTitle [Reason.REBROADCAST] のときだけ、判定に使った比較用タイトル (話数は `#N` で表す)。元のタイトルは変えない
 */
data class DuplicateGroup(
    val reason: Reason,
    val programs: List<Program>,
    val comparisonTitle: String? = null,
) {
    enum class Reason {
        // 同一チャンネル・同一開始時刻
        SAME_BROADCAST,

        // 同一チャンネルで録画時間帯が重なっている
        OVERLAP,

        // 再放送候補: 同一チャンネル・別の時間帯で、比較用タイトルと単一の話数が一致し、長さもほぼ同じ。内容の同一性は保証しない
        REBROADCAST,
    }

    init {
        require(programs.size >= 2) { "a duplicate group needs at least two programs" }
    }

    val recommended: Program get() = programs.first()

    val others: List<Program> get() = programs.drop(1)
}
