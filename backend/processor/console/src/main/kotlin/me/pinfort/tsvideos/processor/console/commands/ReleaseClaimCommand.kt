package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import me.pinfort.tsvideos.console.common.component.UserQuestionComponent
import me.pinfort.tsvideos.core.command.ProcessingJobCommand
import me.pinfort.tsvideos.core.command.VerifiedUploadCommand
import org.springframework.stereotype.Component

/**
 * 処理中に強制終了したプロセスが残したアップロード台帳のクレームを解放する。
 * 台帳の状態と NAS には触れないため、次回の実行が通常どおり再開または reconcile を求める。
 * 実行前にすべての processor / コールバックを停止しておくこと。
 */
@Component
class ReleaseClaimCommand(
    private val processingJobCommand: ProcessingJobCommand,
    private val verifiedUploadCommand: VerifiedUploadCommand,
    private val userQuestionComponent: UserQuestionComponent,
) : CliktCommand(name = "release-claim") {
    override fun help(context: Context): String =
        "release upload claims left by a crashed processor for a recording file (stop every processor and callback first)"

    private val file by argument("file", help = "the recorded file whose upload claims should be released").path()
    private val dryRun by option("-d", "--dry-run").flag(default = false)

    override fun run() {
        val path = file.toFile().canonicalPath
        val job = processingJobCommand.findByPath(path)
        if (job == null) {
            println("no processing job for $path")
            return
        }
        val claimed = verifiedUploadCommand.claimed(job.id)
        if (claimed.isEmpty()) {
            println("no claimed uploads for job ${job.id}")
            return
        }
        claimed.forEach { println("upload ${it.id}: ${it.state} ${it.nasType} ${it.finalPath} claim=${it.claimToken}") }
        if (dryRun) {
            println("in dry run mode.")
            return
        }
        if (!userQuestionComponent.askDefaultFalse("every processor and callback is stopped; release ${claimed.size} claim(s)?")) {
            println("canceled")
            return
        }
        claimed.forEach {
            verifiedUploadCommand.releaseAbandonedClaim(it)
            println("released upload ${it.id}")
        }
    }
}
