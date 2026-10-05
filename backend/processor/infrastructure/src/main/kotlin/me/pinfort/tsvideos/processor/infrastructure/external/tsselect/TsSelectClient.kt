package me.pinfort.tsvideos.processor.infrastructure.external.tsselect

import me.pinfort.tsselect.ProgressListener
import me.pinfort.tsselect.tsDump
import org.springframework.stereotype.Component
import java.io.File

/**
 * me.pinfort.tsselect の tsdump モードで TS を解析し、ドロップ数を数える。
 * 外部プロセス (tsDropChkx64.exe) を起動していた旧 DropChkClient の置き換えで、
 * JVM 内で完結するためプラットフォームに依存しない。
 */
@Component
class TsSelectClient {
    /**
     * 全 PID の連続性カウンタエラー (ドロップ) の合計を返す。
     * 旧 DropChkClient.check が返していた tsDropChk の終了コード (ドロップフレーム数) に相当する。
     *
     * @throws me.pinfort.tsselect.TsSourceOpenException ファイルを開けない場合
     * @throws me.pinfort.tsselect.TsFormatException 188/192/204 バイトの TS パケット列でない場合
     */
    fun check(file: File): Int = check(file) { _, _ -> }

    /**
     * ドロップ数を数えつつ、読み込んだバイト数 / 全体バイト数を [onProgress] に通知する。
     * tsselect は読み込みチャンクごとに 1 回、完了時にもう 1 回コールバックする。
     * 完了通知 (finished) の bytesProcessed は末尾処理分だけ全体より僅かに少ないことがあるため、
     * プログレスバーが 100% で終わるよう totalBytes に丸めて渡す。
     *
     * @throws me.pinfort.tsselect.TsSourceOpenException ファイルを開けない場合
     * @throws me.pinfort.tsselect.TsFormatException 188/192/204 バイトの TS パケット列でない場合
     */
    fun check(
        file: File,
        onProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit,
    ): Int =
        tsDump(
            file,
            ProgressListener { progress ->
                val processed =
                    if (progress.finished && progress.totalBytes > 0) progress.totalBytes else progress.bytesProcessed
                onProgress(processed, progress.totalBytes)
            },
        ).pids.sumOf { it.drop }.toInt()
}
