package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import me.pinfort.tsvideos.processing.api.ChapterBackfillRunner
import me.pinfort.tsvideos.processing.api.ChapterBackfillRunner.Outcome
import me.pinfort.tsvideos.processing.api.ProcessingCancellation
import org.springframework.stereotype.Component

@Component
class ChaptersCommand(
    backfill: ChaptersBackfillCommand,
) : CliktCommand(name = "chapters") {
    override fun help(context: Context): String = "video chapter maintenance"

    init {
        subcommands(backfill)
    }

    override fun run() = Unit
}

/**
 * 既存の mp4 (チャプタ未取得の created_file) のチャプタを ffprobe で取得して記録する。
 * NAS は smbj 経由でしか読めず ffprobe から直接開けないため、VIDEO_STORE_NAS の共有ルートに対応する
 * ローカル/UNC パスをオペレータが渡す。冪等で、中断後に再実行すると未取得分だけを処理する。
 */
@Component
class ChaptersBackfillCommand(
    private val runner: ChapterBackfillRunner,
) : CliktCommand(name = "backfill") {
    override fun help(context: Context): String =
        "probe chapters of registered mp4 files that have none recorded yet, reading them through the local or UNC path " +
            "of the VIDEO_STORE_NAS share root. Files missing or of a different size are skipped. " +
            "Exit 1 if any file was skipped or failed to probe; 0 otherwise"

    private val videoStoreRoot by option(
        "--video-store-root",
        help = "local or UNC path of the VIDEO_STORE_NAS share root, e.g. \\\\nas\\videos",
    ).path(mustExist = true, canBeFile = false, canBeDir = true).required()
    private val dryRun by option("-d", "--dry-run", help = "probe and report only; record nothing").flag(default = false)

    override fun run() {
        if (dryRun) echo("in dry run mode.")
        var summary = ChapterBackfillRunner.Summary()
        try {
            runner.run(videoStoreRoot, dryRun) { result ->
                summary += result.outcome
                echo(describe(result))
            }
        } catch (failure: Exception) {
            // ConsoleRunner turns the interruption into exit 130; the partial counts are still worth showing.
            if (ProcessingCancellation.isInterrupted(failure)) {
                echo("interrupted; recorded files are kept and a re-run continues with the rest.", err = true)
                echo(summarize(summary), err = true)
            }
            throw failure
        }
        echo(summarize(summary))
        if (summary.failed > 0) throw CliktError("${summary.failed} file(s) skipped or failed", statusCode = 1)
    }

    private fun describe(result: ChapterBackfillRunner.FileResult): String {
        val target = "created_file ${result.createdFileId} (${result.localPath ?: result.file})"
        val would = if (dryRun) "would record" else "recorded"
        return when (result.outcome) {
            Outcome.CHAPTERS -> "$would ${result.chapters} chapter(s): $target"
            Outcome.NO_CHAPTERS -> "$would no chapters: $target"
            Outcome.ALREADY_PROBED -> "already probed by another run: $target"
            Outcome.PROBE_FAILED -> "ffprobe failed: $target"
            Outcome.MISSING -> "file missing: $target"
            Outcome.SIZE_MISMATCH -> "size differs from the recorded size: $target"
            Outcome.INVALID_PATH -> "path cannot be joined under the root: $target"
        }
    }

    private fun summarize(summary: ChapterBackfillRunner.Summary): String =
        "probed: ${summary[Outcome.CHAPTERS]} with chapters, ${summary[Outcome.NO_CHAPTERS]} without chapters, " +
            "${summary[Outcome.ALREADY_PROBED]} already probed; " +
            "ffprobe failed: ${summary[Outcome.PROBE_FAILED]}; " +
            "missing/size mismatch: ${summary[Outcome.MISSING]}/${summary[Outcome.SIZE_MISMATCH]}; " +
            "invalid path: ${summary[Outcome.INVALID_PATH]}" +
            if (dryRun) " (dry run: nothing recorded)" else ""
}
