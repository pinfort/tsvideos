package me.pinfort.tsvideos.catalog.domain

/**
 * 録画 (executed_file) に付けるタグ。検出処理が見つけたものをタグとして付け、
 * その検出処理を実行したこと自体は [ExecutedFileCheck] として別に記録する。
 * タグが無い録画が「検出なし」か「未検査」かは、対応するチェックの有無で区別する。
 *
 * 新しい検出を足すときはここに定数を足すだけでよく、DB のスキーマ変更は要らない。
 */
object ExecutedFileTag {
    /** 緊急警報放送 (EWS) */
    const val EWS = "ews"

    /** 文字スーパー */
    const val SUPERIMPOSE = "superimpose"

    /** 字幕 (番組と同期して表示される ARIB 字幕。文字スーパーとは別) */
    const val CAPTION = "caption"

    /** 各タグを付ける検出処理 */
    val CHECKERS: Map<String, String> =
        mapOf(
            EWS to ExecutedFileCheck.EMERGENCY_BROADCAST,
            SUPERIMPOSE to ExecutedFileCheck.EMERGENCY_BROADCAST,
            CAPTION to ExecutedFileCheck.CAPTION,
        )
}

/** 録画に対して実行した検出処理の名前 */
object ExecutedFileCheck {
    /** 緊急警報放送・文字スーパーの検出 */
    const val EMERGENCY_BROADCAST = "emergency_broadcast"

    /**
     * 字幕の検出。[EMERGENCY_BROADCAST] と同じ走査で実行するが、字幕検出の導入前に
     * [EMERGENCY_BROADCAST] だけ記録された録画を「字幕なし」と扱わないよう別の検出処理として記録する。
     */
    const val CAPTION = "caption"
}
