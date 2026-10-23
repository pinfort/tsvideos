package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import me.pinfort.tsvideos.console.common.component.UserQuestionComponent
import me.pinfort.tsvideos.processing.api.ProcessingHistoryCommand
import me.pinfort.tsvideos.processing.api.RecordingQualityCommand
import me.pinfort.tsvideos.processing.domain.QualityAction
import me.pinfort.tsvideos.processing.domain.QualityReleaseRefusedException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class QualityCommand(
    report: QualityReportCommand,
    release: QualityReleaseCommand,
) : CliktCommand(name = "quality") {
    init {
        subcommands(report, release)
    }

    override fun run() = Unit
}

abstract class QualityJobCommand(
    name: String,
    protected val history: ProcessingHistoryCommand,
) : CliktCommand(name = name) {
    protected val jobId by option("--job-id", help = "positive processing job ID").long().required().check { it > 0 }

    final override fun run() {
        try {
            if (history.status(jobId).isEmpty()) throw CliktError("Processing job not found", statusCode = 3)
            execute()
        } catch (error: CliktError) {
            throw error
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CliktError("Quality operation interrupted", statusCode = 130)
        } catch (error: Exception) {
            // The CLI message stays generic; the cause (job ID and exception, no configuration values) goes to the log.
            logger.error("Quality $commandName failed for job $jobId", error)
            throw CliktError("Unable to complete quality operation", statusCode = 1)
        }
    }

    protected abstract fun execute()

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(QualityJobCommand::class.java)
    }
}

@Component
class QualityReportCommand(
    private val quality: RecordingQualityCommand,
    history: ProcessingHistoryCommand,
) : QualityJobCommand("report", history) {
    private val json by option("--json", help = "schema version 1 JSON on stdout").flag()

    private val mapper = JsonMapper.builder().build()

    override fun execute() {
        val reports =
            quality.reports(jobId).map { report ->
                val findings = mapper.readTree(report.findingsJson)
                check(findings.isArray) { "Invalid retained quality evidence" }
                linkedMapOf(
                    "attemptId" to report.attemptId,
                    "jobId" to report.jobId,
                    "checkedAt" to report.checkedAt,
                    "action" to report.action,
                    "sourcePath" to report.sourcePath,
                    "identityKey" to report.identityKey,
                    "quarantineLocation" to report.sourcePath.takeIf { report.action == QualityAction.QUARANTINE },
                    "findings" to findings,
                )
            }
        if (json) {
            echo(mapper.writeValueAsString(linkedMapOf("schemaVersion" to 1, "jobId" to jobId, "reports" to reports)))
        } else {
            if (reports.isEmpty()) echo("No recorded quality reports for job $jobId")
            reports.forEach { echo(mapper.writeValueAsString(it)) }
            if (reports.isNotEmpty()) echo("Reports retain historical decisions; use status for current job state.")
        }
    }
}

@Component
class QualityReleaseCommand(
    private val quality: RecordingQualityCommand,
    history: ProcessingHistoryCommand,
    private val question: UserQuestionComponent,
) : QualityJobCommand("release", history) {
    private val yes by option("--yes", help = "explicitly confirm release without a prompt").flag()
    private val dryRun by option("--dry-run", help = "validate eligibility without changing job state").flag()

    override fun execute() {
        try {
            // Early eligibility check so a refusal never prompts; the real release below revalidates and is authoritative.
            quality.release(jobId, dryRun = true)
            if (dryRun) {
                echo("Job $jobId is eligible for release; no changes made")
                return
            }
            if (!yes && !question.askDefaultFalse("Release quarantined job $jobId for retry? Original remains in place.")) {
                echo("Release canceled")
                return
            }
            quality.release(jobId)
            echo("Job $jobId released to RETRYABLE; original remains in place")
            echo("Processing it under an unchanged quality policy quarantines it again.")
        } catch (refused: QualityReleaseRefusedException) {
            throw CliktError("Release refused (${refused.reason}): ${refused.reason.description}", statusCode = 4)
        }
    }
}
