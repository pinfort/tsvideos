package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import me.pinfort.tsvideos.core.command.RecoveryInspectionCommand
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.domain.ResumeJobMissing
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

abstract class RecoveryInspectionCli(
    name: String,
    private val inspection: RecoveryInspectionCommand,
    private val planning: Boolean,
) : CliktCommand(name = name) {
    private val jobId by option("--job-id").long()
    private val recording by option("--recording")
    private val json by option("--json").flag()
    private val dryRun by option("--dry-run").flag()

    override fun run() {
        if ((jobId != null) == (recording != null) || jobId?.let { it <= 0 } == true || (planning && !dryRun)) {
            throw CliktError("Choose exactly one positive --job-id or --recording; recover requires --dry-run", statusCode = 2)
        }
        try {
            val report = inspection.inspect(jobId, recording)
            if (json) {
                echo(
                    JsonMapper.builder().build().writeValueAsString(
                        mapOf(
                            "schemaVersion" to 1,
                            "operation" to commandName,
                            "dryRun" to true,
                            "inspection" to report,
                        ),
                    ),
                )
            } else {
                echo("${report.status}: ${report.recording ?: "ambiguous recording"}")
                echo("Candidate jobs: ${report.candidateJobIds.joinToString()}")
                report.records.forEach { echo("${it.type} ${it.id}: ${it.status} ${it.path.orEmpty()}") }
                (report.localArtifacts + report.nasArtifacts).forEach { echo("${it.status} ${it.path} [${it.verification}]: ${it.reason}") }
                report.stages.forEach { echo("${it.stage}: ${it.status} / ${it.action}: ${it.reason}") }
                report.uploads.forEach {
                    echo(
                        "UPLOAD ${it.id}: ${it.state} [${it.verification}] owned=${it.owned} final=${it.finalPath} temporary=${it.temporaryPath} source=${it.sourcePath}",
                    )
                }
                report.checkpoints.forEach { (stage, evidence) ->
                    echo("CHECKPOINT $stage: reference=${evidence.referenceId} files=${evidence.files.map { it.path }}")
                }
                report.resumeCommand?.let { echo("Resume command arguments: $it") }
                (report.prerequisites + report.manualOptions).forEach { echo(it) }
            }
            val exit = if (planning && !report.resumePossible) 4 else report.exitCode
            if (exit != 0) throw CliktError("Inspection requires attention; review evidence and prerequisites", statusCode = exit)
        } catch (failure: CliktError) {
            throw failure
        } catch (_: ResumeJobMissing) {
            throw CliktError("Recording or job not found", statusCode = 3)
        } catch (failure: Exception) {
            if (ProcessingCancellation.isInterrupted(failure)) throw CliktError("Inspection interrupted", statusCode = 130)
            throw CliktError("Inspection unavailable; no recovery action was executed", statusCode = 1)
        }
    }
}

@Component
class InspectCommand(
    inspection: RecoveryInspectionCommand,
) : RecoveryInspectionCli("inspect", inspection, false)

@Component
class RecoverCommand(
    inspection: RecoveryInspectionCommand,
) : RecoveryInspectionCli("recover", inspection, true)
