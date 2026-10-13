package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import me.pinfort.tsvideos.processor.console.display.RecordingProgressPrinter
import me.pinfort.tsvideos.processor.infrastructure.pipeline.BatchNotification
import me.pinfort.tsvideos.processor.infrastructure.pipeline.PathProcessingRunner
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path

@Component
class ProcessCommand(
    private val pathProcessingRunner: PathProcessingRunner,
) : CliktCommand(name = "process") {
    override fun help(context: Context): String = "process recordings and report per-recording outcomes"

    private val paths by argument("paths", help = "file or directory containing .m2ts files").multiple(required = true)
    private val dryRun by option("-d", "--dry-run").flag()
    private val json by option("--json", help = "one schemaVersion 1 JSON report on stdout").flag()
    private val notifications by option("--slack", help = "notification policy; dry-run never notifies")
        .choice("per-file" to BatchNotification.PER_FILE, "summary" to BatchNotification.SUMMARY, "none" to BatchNotification.NONE)
        .default(BatchNotification.PER_FILE)

    override fun run() {
        val printer = RecordingProgressPrinter { echo(it, err = true) }
        val report =
            pathProcessingRunner.processReport(
                paths.map { Path.of(it) },
                dryRun,
                notifications,
                printer::progress,
                printer::complete,
            )
        if (json) {
            echo(JsonMapper.builder().build().writeValueAsString(linkedMapOf("schemaVersion" to 1, "batch" to report)))
        } else {
            echo(
                "Submitted ${report.submitted}; skipped ${report.skipped}; dry-run ${report.planned}; failed ${report.failed}; " +
                    "interrupted ${report.interruptedRecordings}; not started ${report.notStarted}",
            )
            report.recordings.forEach {
                echo("[${it.ordinal}] ${it.path}: ${it.outcome} (${it.reasonCode}), ${it.elapsedMillis} ms")
                it.stageTimings.forEach { stage -> echo("  ${stage.stage}: ${stage.elapsedMillis} ms") }
            }
            if (!report.completeDiscovery) echo("Input discovery incomplete")
        }
        if (report.exitCode != 0) throw CliktError("Processing batch requires attention", statusCode = report.exitCode)
    }
}
