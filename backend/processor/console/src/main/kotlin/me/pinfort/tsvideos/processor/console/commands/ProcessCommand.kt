package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.pinfort.tsvideos.processor.console.display.ProgressPrinter
import me.pinfort.tsvideos.processor.infrastructure.pipeline.PathProcessingRunner
import org.springframework.stereotype.Component
import java.nio.file.Path

/** PreflightCommand's process gate parses these same arguments first; add new options there too. */
@Component
class ProcessCommand(
    private val pathProcessingRunner: PathProcessingRunner,
) : CliktCommand(name = "process") {
    override fun help(context: Context): String =
        "process ts recording files (drop check -> quality check -> split -> compress -> encode queue). " +
            "Exit 4 if any recording is quarantined, or would be in --dry-run; 1 if any failed; 0 otherwise"

    private val paths by argument("paths", help = "file or directory containing .m2ts files").multiple(required = true)
    private val dryRun by option("-d", "--dry-run").flag(default = false)

    private val dropCheckProgressPrinter = ProgressPrinter("Drop checking")
    private val emergencyCheckProgressPrinter = ProgressPrinter("Emergency checking")
    private val compressProgressPrinter = ProgressPrinter("Compressing")
    private val uploadProgressPrinter = ProgressPrinter("Uploading")

    override fun run() {
        var summary = PathProcessingRunner.Summary()
        paths.forEach {
            summary +=
                pathProcessingRunner.processPath(
                    Path.of(it),
                    dryRun,
                    dropCheckProgressPrinter::render,
                    emergencyCheckProgressPrinter::render,
                    compressProgressPrinter::render,
                    uploadProgressPrinter::render,
                )
        }
        if (summary.quarantined > 0) {
            throw CliktError("${summary.quarantined} recording(s) quarantined; ${summary.failed} failed", statusCode = 4)
        }
        if (summary.failed > 0) throw CliktError("${summary.failed} recording(s) failed", statusCode = 1)
    }
}
