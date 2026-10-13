package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import me.pinfort.tsvideos.processor.console.display.RecordingProgressPrinter
import me.pinfort.tsvideos.processor.infrastructure.pipeline.PathProcessingRunner
import me.pinfort.tsvideos.processor.infrastructure.pipeline.WatchOptions
import me.pinfort.tsvideos.processor.infrastructure.pipeline.WatchRunner
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class WatchCommand(
    private val runner: WatchRunner,
) : CliktCommand(name = "watch") {
    override fun help(context: Context): String = "Watch explicit directories; prefer completion markers when available."

    private val directories by argument("directories").multiple(required = true)
    private val completion by option("--completion").choice("marker", "stable").required()
    private val markerSuffix by option("--marker-suffix").default(".ready")
    private val stableSeconds by option("--stable-seconds").long().default(30)
    private val pollSeconds by option("--poll-seconds").long().default(5)
    private val extensions by option("--extension").multiple(default = listOf(".m2ts"))
    private val capacity by option("--max-observations").int().default(10000)
    private val dryRun by option("-d", "--dry-run").flag(default = false)
    private val printer = RecordingProgressPrinter()

    override fun run() {
        val options =
            try {
                WatchOptions(
                    directories.map { Path.of(it) },
                    completion,
                    markerSuffix,
                    stableSeconds,
                    pollSeconds,
                    extensions.toSet(),
                    capacity,
                )
            } catch (_: IllegalArgumentException) {
                throw CliktError(
                    "Invalid watch configuration: check directories, positive intervals, extension/suffix and capacity limits.",
                    statusCode = 2,
                )
            }
        val code = runner.run(options, dryRun, { printer.complete(PathProcessingRunner.Completion(it.path, it.code)) }, printer::progress)
        if (code !=
            0
        ) {
            throw CliktError("Watch admission stopped: capacity exceeded. Restart or increase --max-observations.", statusCode = code)
        }
    }
}
