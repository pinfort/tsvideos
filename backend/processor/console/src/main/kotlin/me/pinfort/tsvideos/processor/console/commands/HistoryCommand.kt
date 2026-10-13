package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import me.pinfort.tsvideos.core.command.ProcessingHistoryCommand
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

abstract class HistoryReadCommand(
    name: String,
    protected val history: ProcessingHistoryCommand,
) : CliktCommand(name = name) {
    protected val jobId by option("--job-id", help = "select an existing or retained numeric job ID").long().check { it > 0 }
    private val all by option("--all", help = "all jobs (default)").flag()
    protected val json by option("--json", help = "schema version 1 JSON on stdout").flag()

    final override fun run() {
        if (all && jobId != null) throw UsageError("--all and --job-id are mutually exclusive")
        try {
            read()
        } catch (error: CliktError) {
            throw error
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CliktError("History read interrupted", statusCode = 130)
        } catch (_: Exception) {
            throw CliktError("Unable to read processing history", statusCode = 1)
        }
    }

    protected abstract fun read()

    protected fun output(
        key: String,
        value: Any,
    ) = echo(
        JsonMapper.builder().build().writeValueAsString(
            linkedMapOf(
                "schemaVersion" to 1,
                key to value,
            ),
        ),
    )

    protected fun missing(): Nothing = throw CliktError("Processing job not found", statusCode = 3)
}

@Component
class StatusCommand(
    history: ProcessingHistoryCommand,
) : HistoryReadCommand("status", history) {
    override fun read() {
        val jobs = history.status(jobId)
        if (jobId != null && jobs.isEmpty()) missing()
        if (json) {
            output("jobs", jobs)
        } else {
            if (jobs.isEmpty()) echo("No processing jobs")
            jobs.forEach { job ->
                echo("Job ${job.jobId}: ${job.jobState} | ${job.inputPath}")
                val latest = job.latestAttempt
                if (latest == null) {
                    echo("  No recorded attempts")
                } else {
                    echo("  Attempt ${latest.attemptNumber} ${latest.kind} ${latest.outcome}; activity ${latest.activityAt}")
                }
                job.activeAttempts.forEach { echo("  Active attempt ${it.attemptNumber}; owner liveness is not inferred from age") }
            }
        }
    }
}

@Component
class HistoryCommand(
    history: ProcessingHistoryCommand,
) : HistoryReadCommand("history", history) {
    override fun read() {
        if (jobId != null && history.status(jobId).isEmpty()) missing()
        val attempts = history.history(jobId)
        if (json) {
            output("attempts", attempts)
        } else {
            if (attempts.isEmpty()) echo("No recorded attempts")
            attempts.forEach { record ->
                val attempt = record.attempt
                echo("Job ${attempt.jobId} attempt ${attempt.attemptNumber} ${attempt.kind} ${attempt.outcome}")
                echo("  ${attempt.startedAt} -> ${attempt.endedAt ?: "active"}; error ${attempt.errorCode ?: "none"}")
                record.encodingProfile?.let { echo("  Profile ${it.profile}; rule ${it.ruleId}; ${it.reason}") }
                record.stages.forEach { stage ->
                    echo("  ${stage.stage}: ${stage.outcome} ${stage.startedAt} -> ${stage.endedAt ?: "active"}")
                    if (stage.artifactType != null) echo("    ${stage.artifactType} ${stage.artifactId ?: ""} ${stage.artifactPath ?: ""}")
                }
            }
        }
    }
}
