package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.domain.ResumeBlocked
import me.pinfort.tsvideos.core.domain.ResumeJobMissing
import me.pinfort.tsvideos.processor.infrastructure.pipeline.ProcessingResumeRunner
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class ResumeCommand(
    private val runner: ProcessingResumeRunner,
) : CliktCommand(name = "resume") {
    private val jobId by option("--job-id").long().required().check { it > 0 }
    private val dryRun by option("--dry-run").flag()
    private val json by option("--json").flag()

    override fun run() {
        try {
            val plan = runner.run(jobId, dryRun)
            if (json) {
                echo(JsonMapper.builder().build().writeValueAsString(mapOf("schemaVersion" to 1, "dryRun" to dryRun, "resume" to plan)))
            } else {
                val suffix = if (dryRun) " (dry run)" else ""
                echo("Job ${plan.jobId}: ${plan.mode}$suffix")
                plan.stages.forEach {
                    echo("${it.stage}: ${it.action} — ${it.reason}")
                    it.prerequisites.forEach { action ->
                        echo("  $action")
                    }
                }
            }
            if (plan.blocked) throw CliktError("Resume blocked; inspect the reported prerequisites", statusCode = 4)
        } catch (e: CliktError) {
            throw e
        } catch (_: ResumeJobMissing) {
            throw CliktError("Processing job not found", statusCode = 3)
        } catch (
            _: ResumeBlocked,
        ) {
            throw CliktError("Resume blocked: ownership or prerequisites changed; inspect a new dry-run", statusCode = 4)
        } catch (_: IllegalArgumentException) {
            throw UsageError("Invalid resume input")
        } catch (e: Exception) {
            if (ProcessingCancellation.isInterrupted(e)) {
                Thread.currentThread().interrupt()
                throw CliktError("Resume interrupted", statusCode = 130)
            }
            throw CliktError("Resume failed; inspect durable history and checkpoint evidence", statusCode = 1)
        }
    }
}
