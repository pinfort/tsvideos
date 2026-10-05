package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.pinfort.tsvideos.processor.console.display.ProgressPrinter
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

    private val dropCheckProgressPrinter = ProgressPrinter("Drop checking")
    private val compressProgressPrinter = ProgressPrinter("Compressing")
    private val uploadProgressPrinter = ProgressPrinter("Uploading")

    override fun run() {
        paths.forEach {
            pathProcessingRunner.processPath(
                Path.of(it),
                dryRun,
                dropCheckProgressPrinter::render,
                compressProgressPrinter::render,
                uploadProgressPrinter::render,
            )
        }
    }
}
