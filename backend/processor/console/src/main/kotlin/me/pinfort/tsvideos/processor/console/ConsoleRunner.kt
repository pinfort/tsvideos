package me.pinfort.tsvideos.processor.console

import com.github.ajalt.clikt.core.main
import me.pinfort.tsvideos.processor.console.commands.TsVideosProcessor
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class ConsoleRunner(
    private val tsVideosProcessor: TsVideosProcessor,
) : CommandLineRunner {
    override fun run(vararg args: String) =
        tsVideosProcessor.main(
            me.pinfort.tsvideos.core.config.DiagnosticEnvironment
                .commandArguments(args.toList().toTypedArray())
                .toTypedArray(),
        )
}
