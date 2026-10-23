package me.pinfort.tsvideos.processor.console

import me.pinfort.tsvideos.catalog.api.CatalogPersistence
import me.pinfort.tsvideos.console.common.charset.ConsoleCharset
import me.pinfort.tsvideos.diagnostics.api.DiagnosticEnvironment
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@MapperScan(
    basePackages = [
        "me.pinfort.tsvideos.core.external.database.mapper",
        CatalogPersistence.MAPPER_PACKAGE,
    ],
)
@SpringBootApplication(
    scanBasePackages = [
        "me.pinfort.tsvideos.storage",
        "me.pinfort.tsvideos.catalog",
        "me.pinfort.tsvideos.diagnostics",
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
    ConsoleCharset.configureLogging()
    if (hasOption(args, "--json")) System.setOut(java.io.PrintStream(System.out, true, Charsets.UTF_8))
    val command = DiagnosticEnvironment.commandArguments(args).firstOrNull()
    if (command == "preflight") exitProcess(PreflightBootstrap.run(args))
    if (command == "process" && !hasOption(args, "--help")) {
        val status = PreflightBootstrap.run(args, processGate = true)
        if (status != 0) exitProcess(status)
    }
    runApplication<ConsoleApplication>(*args) { setRegisterShutdownHook(false) }.close()
}
