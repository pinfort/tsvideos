package me.pinfort.tsvideos.processor.console

import me.pinfort.tsvideos.core.config.DiagnosticEnvironment
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@MapperScan("me.pinfort.tsvideos.core.external.database.mapper")
@SpringBootApplication(
    scanBasePackages = [
        "me.pinfort.tsvideos.core",
        "me.pinfort.tsvideos.console.common",
        "me.pinfort.tsvideos.processor",
    ],
)
@ConfigurationPropertiesScan(
    basePackages = [
        "me.pinfort.tsvideos.core.config",
    ],
)
class ConsoleApplication

fun main(args: Array<String>) {
    if (hasOption(args, "--json")) System.setOut(java.io.PrintStream(System.out, true, Charsets.UTF_8))
    val command = DiagnosticEnvironment.commandArguments(args).firstOrNull()
    if (command == "preflight") exitProcess(PreflightBootstrap.run(args))
    if (command == "process" && !hasOption(args, "--help")) {
        val status = PreflightBootstrap.run(args, processGate = true)
        if (status != 0) exitProcess(status)
    }
    runApplication<ConsoleApplication>(*args)
}
