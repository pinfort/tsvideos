package me.pinfort.tsvideos.core.domain

import java.time.LocalDateTime

data class ProgramDetail(
    val id: Long,
    val name: String,
    val executedFileId: Long,
    val status: Program.Status,
    val drops: Int,
    val size: Long,
    val recordedAt: LocalDateTime,
    val channel: String,
    val title: String,
    val channelName: String,
    val duration: Double,
    val createdFiles: List<CreatedFile>,
    val tags: List<String> = emptyList(),
    val checks: List<String> = emptyList(),
) {
    /**
     * [tag] を付ける検出の結果。タグがあれば true、検出処理を実行済みでタグが無ければ false、
     * 未実行 (検出処理の導入前に登録された録画など) なら null。
     */
    fun detected(tag: String): Boolean? =
        when {
            tag in tags -> true
            ExecutedFileTag.CHECKERS[tag] in checks -> false
            else -> null
        }
}
