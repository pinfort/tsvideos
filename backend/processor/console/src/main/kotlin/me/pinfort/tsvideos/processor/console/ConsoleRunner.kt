package me.pinfort.tsvideos.processor.console

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.config.DiagnosticEnvironment
import me.pinfort.tsvideos.processor.console.commands.TsVideosProcessor
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

@Component
class ConsoleRunner(
    private val processor: TsVideosProcessor,
    private val cancellation: ProcessingCancellation,
    private val context: ConfigurableApplicationContext,
    @Value("\${processor.shutdown.grace-seconds:30}") graceSeconds: Long,
) : CommandLineRunner {
    // Built with the bean so an out-of-range grace fails context startup rather than the first run.
    private val shutdown = ProcessorShutdown(cancellation, graceSeconds) { context.close() }

    override fun run(vararg args: String) {
        val hook = Thread({ shutdown.stop() }, "processor-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            cancellation.runOwned { processor.parseProcessor(DiagnosticEnvironment.commandArguments(args.toList().toTypedArray())) }
            // A normal return needs no stop; main closes the context itself.
            removeHook(hook)
        } catch (error: InterruptedException) {
            exitProcess(130)
        } catch (error: CliktError) {
            processor.echoFormattedHelp(error)
            exitProcess(error.statusCode)
        } catch (error: Exception) {
            if (ProcessingCancellation.isInterrupted(error)) exitProcess(130)
            throw error
        }
    }

    private fun removeHook(hook: Thread) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (_: IllegalStateException) {
            // The JVM is already shutting down; the hook is running or about to.
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
