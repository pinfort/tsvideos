package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import me.pinfort.tsvideos.core.command.CleanupPlanCommand
import me.pinfort.tsvideos.core.command.CleanupResourceMissing
import me.pinfort.tsvideos.core.domain.BulkInterruptedException
import me.pinfort.tsvideos.core.domain.BulkOutcome
import me.pinfort.tsvideos.core.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.core.domain.CleanupCriteria
import me.pinfort.tsvideos.core.domain.CleanupMode
import me.pinfort.tsvideos.manager.console.component.CleanupPlanJson
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.DateTimeException

@Component
class Cleanup(
    command: CleanupPlanCommand,
) : CliktCommand() {
    init {
        subcommands(CleanupPlanCli(command), CleanupApplyCli(command))
    }

    override fun help(context: Context): String = "generate and explicitly apply reviewed JSON cleanup plans"

    override fun run() = Unit
}

class CleanupPlanCli(
    private val command: CleanupPlanCommand,
) : CliktCommand(name = "plan") {
    private val duplicates by option("--duplicates").flag()
    private val before by option("--recorded-before", help = "exclusive ISO local recording cutoff")
    private val channel by option("--channel", help = "exact channel")
    private val keep by option("--keep", help = "override kept copy ID (repeat per group)").long().multiple()
    private val json by option("--json", help = "JSON is always emitted").flag()

    override fun run() =
        cleanupErrors {
            if (duplicates == (before != null)) throw UsageError("Choose --duplicates OR an explicit --recorded-before cutoff")
            val criteria = CleanupCriteria(if (duplicates) CleanupMode.DUPLICATES else CleanupMode.RETENTION, before, channel, keep)
            echo(CleanupPlanJson.write(command.plan(criteria)))
        }
}

class CleanupApplyCli(
    private val command: CleanupPlanCommand,
) : CliktCommand(name = "apply") {
    private val file by argument("plan-file")
    private val dryRun by option(
        "--dry-run",
        help = "validate each target against current state; earlier deletions are not simulated, so apply can still be STALE",
    ).flag()
    private val yes by option("--yes", help = "confirm application of the reviewed plan").flag()
    private val json by option("--json", help = "JSON is always emitted").flag()

    override fun run() =
        cleanupErrors {
            if (!dryRun && !yes) throw UsageError("Review the plan, then use --yes to apply it (or --dry-run to validate)")
            val path = Path.of(file)
            if (!Files.isRegularFile(path)) throw CliktError("Plan file not found", statusCode = 3)
            require(Files.size(path) <= 4 * 1024 * 1024) { "Plan file too large" }
            val plan =
                try {
                    CleanupPlanJson.read(Files.readString(path))
                } catch (_: Exception) {
                    throw UsageError("Invalid cleanup plan JSON")
                }
            val result =
                try {
                    command.apply(plan, dryRun)
                } catch (error: BulkInterruptedException) {
                    // Deletions are irreversible: report what already happened before exiting 130.
                    echo(outcomesJson(dryRun, error.completed, mapOf("interrupted" to true, "interruptedTargetId" to error.interruptedId)))
                    throw error
                }
            echo(outcomesJson(dryRun, result.outcomes))
            unresolvedExitCode(result.outcomes)?.let { throw CliktError("Cleanup has unresolved targets", statusCode = it) }
        }
}

private fun outcomesJson(
    dryRun: Boolean,
    outcomes: List<BulkOutcome>,
    extra: Map<String, Any?> = emptyMap(),
): String =
    CleanupPlanJson.write(mapOf("schemaVersion" to 1, "cleanup" to mapOf("dryRun" to dryRun) + extra + mapOf("outcomes" to outcomes)))

/** 3 only when every target is gone; any other mix with a non-SUCCESS outcome (including partial success) is 4. */
private fun unresolvedExitCode(outcomes: List<BulkOutcome>): Int? =
    when {
        outcomes.all { it.status == BulkOutcomeStatus.SUCCESS } -> null
        outcomes.all { it.status == BulkOutcomeStatus.NOT_FOUND } -> 3
        else -> 4
    }

private fun cleanupErrors(block: () -> Unit) {
    try {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Cleanup interrupted")
        block()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw CliktError("Cleanup interrupted; inspect current state before retrying", statusCode = 130)
    } catch (error: CliktError) {
        throw error
    } catch (_: CleanupResourceMissing) {
        throw CliktError("Selected recording not found", statusCode = 3)
    } catch (_: DateTimeException) {
        throw UsageError("Invalid cleanup date")
    } catch (error: IllegalArgumentException) {
        // Validation messages describe the rejected input only, so they are safe to show.
        throw UsageError("Invalid cleanup criteria or plan" + (error.message?.let { ": $it" } ?: ""))
    } catch (error: Exception) {
        if (Thread.currentThread().isInterrupted || generateSequence<Throwable>(error) { it.cause }.any { it is InterruptedException }) {
            Thread.currentThread().interrupt()
            throw CliktError("Cleanup interrupted; inspect current state before retrying", statusCode = 130)
        }
        throw CliktError("Cleanup operation failed", statusCode = 1)
    }
}
