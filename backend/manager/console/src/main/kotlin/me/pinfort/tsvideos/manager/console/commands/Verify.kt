package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import me.pinfort.tsvideos.core.command.LibraryVerificationCommand
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class Verify(
    private val libraryVerificationCommand: LibraryVerificationCommand,
) : CliktCommand() {
    override fun help(context: Context): String = "Read-only NAS existence and size checks (all programs by default)"

    private val programId by option("--program", help = "Verify only this program ID").long().check("must be positive") { it > 0 }
    private val json by option("--json", help = "Emit schemaVersion 1 JSON").flag()

    override fun run() {
        val report =
            try {
                libraryVerificationCommand.verify(programId, ::reportProgress)
            } catch (error: Exception) {
                // Driver exceptions can contain credentials or connection strings, so only the class is logged.
                logger.error("Library verification failed: {}", error.javaClass.name)
                throw CliktError("Library verification failed", statusCode = 1)
            } ?: throw CliktError("Program not found", statusCode = 3)
        if (json) {
            val payload =
                mapOf(
                    "schemaVersion" to 1,
                    "summary" to report.summary.mapKeys { it.key.name },
                    "findings" to
                        report.findings.map {
                            mapOf(
                                "programId" to it.programId,
                                "fileId" to it.fileId,
                                "file" to it.file,
                                "status" to it.status.name,
                                "checkedAt" to it.checkedAt.toString(),
                                "expectedBytes" to it.expectedBytes,
                                "actualBytes" to it.actualBytes,
                                "reason" to it.reason,
                            )
                        },
                )
            echo(mapper.writeValueAsString(payload))
        } else {
            report.findings.forEach {
                echo("${it.status}\tprogram=${it.programId}\tfile=${it.fileId ?: "-"}\t${it.file ?: "-"}")
                echo(
                    "  expected=${it.expectedBytes ?: "-"} actual=${it.actualBytes ?: "-"} checkedAt=${it.checkedAt}${it.reason?.let { r ->
                        " $r"
                    } ?: ""}",
                )
            }
            echo(report.summary.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }
        if (report.hasIssues) throw ProgramResult(4)
    }

    /** Progress goes to stderr so `--json` stdout stays a single object. */
    private fun reportProgress(
        checked: Int,
        total: Int,
    ) {
        if (checked % PROGRESS_INTERVAL == 0 || (checked == total && total > PROGRESS_INTERVAL)) {
            echo("Checked $checked/$total programs", err = true)
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL = 100
        val mapper: JsonMapper = JsonMapper.builder().build()
        val logger = LoggerFactory.getLogger(Verify::class.java)
    }
}
