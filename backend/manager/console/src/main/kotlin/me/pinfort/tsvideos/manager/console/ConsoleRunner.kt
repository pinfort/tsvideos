package me.pinfort.tsvideos.manager.console

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import me.pinfort.tsvideos.manager.console.commands.TsVideosManager
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

@Component
class ConsoleRunner(
    private val tsVideosManager: TsVideosManager,
) : CommandLineRunner {
    override fun run(vararg args: String) {
        try {
            tsVideosManager.parseManager(args.toList())
        } catch (error: CliktError) {
            tsVideosManager.echoFormattedHelp(error)
            exitProcess(error.statusCode)
        }
    }
}

/** Clikt defaults usage errors to 1; manager reserves 2 for invalid input. */
fun CliktCommand.parseManager(args: List<String>) {
    try {
        parse(args)
    } catch (error: UsageError) {
        throw CliktError(getFormattedHelp(error), statusCode = 2)
    }
}
