package me.pinfort.tsvideos.core.domain

/**
 * 同じ放送を録画したと判定された番組のまとまり
 *
 * @param programs 残すべき順に並んだ番組。先頭が残す候補
 */
data class DuplicateGroup(
    val reason: Reason,
    val programs: List<Program>,
) {
    enum class Reason {
        // 同一チャンネル・同一開始時刻
        SAME_BROADCAST,

        // 同一チャンネルで録画時間帯が重なっている
        OVERLAP,
    }

    val recommended: Program get() = programs.first()

    val others: List<Program> get() = programs.drop(1)
}
