package me.pinfort.tsvideos.processor.console

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.pinfort.tsvideos.core.command.EnvironmentDiagnosticCommand
import me.pinfort.tsvideos.core.command.ProcessingPreflightCommand
import me.pinfort.tsvideos.core.component.DatabaseDiagnosticProbe
import me.pinfort.tsvideos.core.component.EncoderConnectivityProbe
import me.pinfort.tsvideos.core.component.LocalPreflightProbe
import me.pinfort.tsvideos.core.component.NasDiagnosticProbe
import me.pinfort.tsvideos.core.config.DiagnosticEnvironment
import me.pinfort.tsvideos.core.domain.DiagnosticCheck
import me.pinfort.tsvideos.core.domain.DiagnosticStatus
import me.pinfort.tsvideos.core.external.samba.DiagnosticNasClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path

class PreflightCommand(
    private val diagnostics: ProcessingPreflightCommand,
    private val processGate: Boolean = false,
) : CliktCommand(name = if (processGate) "process" else "preflight") {
    private val paths by argument("paths", help = "recording files or directories containing .m2ts files").multiple(required = true)
    private val json by option("--json").flag()
    private val checkWrite by option("--check-write", help = "explicit isolated NAS write/read/delete probe").flag()
    private val dryRun by option("-d", "--dry-run", help = "read-only checks; incompatible with --check-write").flag()

    override fun run() {
        if (checkWrite && (dryRun || processGate)) throw UsageError("--check-write is only available for explicit non-dry-run preflight")
        if (processGate && json) throw UsageError("Use preflight --json for a structured report")
        val parsed =
            try {
                paths.map(Path::of)
            } catch (_: Exception) {
                throw UsageError("Invalid input path")
            }
        val report = diagnostics.diagnose(parsed, checkWrite)
        if (!processGate || report.hasFailures) {
            echo(renderChecks(report.checks, json))
        } else {
            report.checks.filter { it.status == DiagnosticStatus.UNCHECKED }.forEach {
                echo(
                    "${it.status}\t${it.id}\t${it.reason}",
                    err = true,
                )
            }
        }
        if (report.hasFailures) throw ProgramResult(1)
    }
}

internal fun renderChecks(
    checks: List<DiagnosticCheck>,
    json: Boolean,
): String =
    if (json) {
        JsonMapper.builder().build().writeValueAsString(mapOf("schemaVersion" to 1, "checks" to checks))
    } else {
        checks.joinToString("\n") { "${it.status}\t${it.id}\t${it.reason}" }
    }

/** Diagnose before building the normal processor graph, even when its typed config is invalid. */
object PreflightBootstrap {
    fun run(
        args: Array<String>,
        processGate: Boolean = false,
    ): Int {
        val log = (LoggerFactory.getILoggerFactory() as? LoggerContext)?.getLogger(Logger.ROOT_LOGGER_NAME)
        val previous = log?.level
        log?.level = Level.OFF
        try {
            val environment =
                try {
                    if ("--help" in args) {
                        org.springframework.core.env
                            .StandardEnvironment()
                    } else {
                        DiagnosticEnvironment.load(args)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return 130
                } catch (_: Exception) {
                    if (Thread.currentThread().isInterrupted) return 130
                    println(
                        renderChecks(
                            listOf(
                                DiagnosticCheck(
                                    "config.load",
                                    DiagnosticStatus.FAIL,
                                    "Check application configuration files and unresolved placeholders",
                                ),
                            ),
                            "--json" in args,
                        ),
                    )
                    return 1
                }
            val command =
                PreflightCommand(
                    ProcessingPreflightCommand(
                        environment,
                        EnvironmentDiagnosticCommand(environment, DatabaseDiagnosticProbe(), NasDiagnosticProbe(DiagnosticNasClient())),
                        LocalPreflightProbe(),
                        EncoderConnectivityProbe(),
                    ),
                    processGate,
                )
            return try {
                command.parse(DiagnosticEnvironment.commandArguments(args).drop(1))
                0
            } catch (error: UsageError) {
                command.echoFormattedHelp(CliktError("Invalid preflight arguments: check paths and option combinations", statusCode = 2))
                2
            } catch (error: CliktError) {
                command.echoFormattedHelp(error)
                error.statusCode
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                System.err.println("Preflight interrupted")
                130
            } catch (_: Exception) {
                System.err.println("Unable to complete preflight; check configuration and access")
                1
            }
        } finally {
            log?.level = previous
        }
    }
}
