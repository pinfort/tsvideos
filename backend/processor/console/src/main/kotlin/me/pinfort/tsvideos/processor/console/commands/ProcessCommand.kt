package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.pinfort.tsvideos.processor.console.display.RecordingProgressPrinter
import me.pinfort.tsvideos.processor.infrastructure.pipeline.PathProcessingRunner
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class ProcessCommand(
    private val pathProcessingRunner: PathProcessingRunner,
) : CliktCommand(name = "process") {
    override fun help(context: Context): String = "process ts recording files (drop check -> split -> compress -> encode queue)"

    private val paths by argument("paths", help = "file or directory containing .m2ts files").multiple(required = true)
    private val dryRun by option("-d", "--dry-run").flag(default = false)

    private val printer = RecordingProgressPrinter()

    override fun run() {
        val result = pathProcessingRunner.processPaths(paths.map { Path.of(it) }, dryRun, printer::progress, printer::complete)
        if (result.failed > 0) throw CliktError("${result.failed} recording(s) failed; ${result.completed} completed", statusCode = 1)
    }
}
