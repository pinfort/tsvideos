package me.pinfort.tsvideos.manager.console

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.github.ajalt.clikt.core.CliktError
import me.pinfort.tsvideos.core.command.EnvironmentDiagnosticCommand
import me.pinfort.tsvideos.core.component.DatabaseDiagnosticProbe
import me.pinfort.tsvideos.core.component.NasDiagnosticProbe
import me.pinfort.tsvideos.core.domain.DiagnosticCheck
import me.pinfort.tsvideos.core.domain.DiagnosticStatus
import me.pinfort.tsvideos.core.external.samba.DiagnosticNasClient
import me.pinfort.tsvideos.manager.console.commands.Doctor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.env.StandardEnvironment
import tools.jackson.databind.json.JsonMapper

/** Load Boot config data, but never create the manager's datasource or eager NAS command graph. */
object DoctorBootstrap {
    fun commandArguments(args: Array<String>): List<String> = args.filterNot { it.startsWith("--spring.") && '=' in it }

    fun loadEnvironment(args: Array<String>): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(
                SimpleCommandLinePropertySource(*args.filter { it.startsWith("--spring.") && '=' in it }.toTypedArray()),
            )
            ConfigDataEnvironmentPostProcessor.applyTo(this)
        }

    fun run(args: Array<String>): Int {
        // JDBC/SMB libraries may log credential-bearing exceptions. Doctor emits its own safe findings.
        (LoggerFactory.getILoggerFactory() as? LoggerContext)?.getLogger(Logger.ROOT_LOGGER_NAME)?.level = Level.OFF
        val environment =
            try {
                if ("--help" in commandArguments(args)) StandardEnvironment() else loadEnvironment(args)
            } catch (_: Exception) {
                val finding =
                    DiagnosticCheck(
                        "config.load",
                        DiagnosticStatus.FAIL,
                        "Check application configuration files, profile names and unresolved placeholders",
                    )
                if ("--json" in args) {
                    println(JsonMapper.builder().build().writeValueAsString(mapOf("schemaVersion" to 1, "checks" to listOf(finding))))
                } else {
                    println("${finding.status}\t${finding.id}\t${finding.reason}")
                }
                return 1
            }
        val doctor = Doctor(EnvironmentDiagnosticCommand(environment, DatabaseDiagnosticProbe(), NasDiagnosticProbe(DiagnosticNasClient())))
        return try {
            doctor.parseManager(commandArguments(args).drop(1))
            0
        } catch (error: CliktError) {
            doctor.echoFormattedHelp(error)
            error.statusCode
        }
    }
}
