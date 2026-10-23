package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.pinfort.tsvideos.diagnostics.api.EnvironmentDiagnosticCommand
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class Doctor(
    private val diagnostics: EnvironmentDiagnosticCommand,
) : CliktCommand() {
    override fun help(context: Context): String = "Diagnose configuration, database and NAS access without changing the library"

    private val json by option("--json", help = "Emit schemaVersion 1 checks").flag()
    private val checkWrite by option(
        "--check-write",
        help = "Create and remove an isolated temporary file in each NAS base directory",
    ).flag()

    override fun run() {
        val report = diagnostics.diagnose(checkWrite)
        if (json) {
            echo(JsonMapper.builder().build().writeValueAsString(mapOf("schemaVersion" to 1, "checks" to report.checks)))
        } else {
            report.checks.forEach { echo("${it.status}\t${it.id}\t${it.reason}") }
        }
        if (report.hasFailures) throw ProgramResult(1)
    }
}
