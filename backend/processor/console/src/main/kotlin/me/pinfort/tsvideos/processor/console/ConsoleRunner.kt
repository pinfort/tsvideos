package me.pinfort.tsvideos.processor.console

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import me.pinfort.tsvideos.processor.console.commands.TsVideosProcessor
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

@Component
class ConsoleRunner(
    private val processor: TsVideosProcessor,
) : CommandLineRunner {
    override fun run(vararg args: String) {
        try {
            processor.parseProcessor(args.toList())
        } catch (error: CliktError) {
            processor.echoFormattedHelp(error)
            exitProcess(error.statusCode)
        }
    }
}

/** Match the shared machine CLI convention: invalid arguments return 2. */
fun CliktCommand.parseProcessor(args: List<String>) {
    try {
        parse(args)
    } catch (error: UsageError) {
        throw CliktError(getFormattedHelp(error), statusCode = 2)
    }
}
