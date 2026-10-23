package me.pinfort.tsvideos.manager.console

import me.pinfort.tsvideos.catalog.api.CatalogPersistence
import me.pinfort.tsvideos.console.common.charset.ConsoleCharset
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

@MapperScan(basePackages = ["me.pinfort.tsvideos.core.external.database.mapper", CatalogPersistence.MAPPER_PACKAGE])
@SpringBootApplication(
    scanBasePackages = [
        "me.pinfort.tsvideos.storage",
        "me.pinfort.tsvideos.catalog",
        "me.pinfort.tsvideos.diagnostics",
        "me.pinfort.tsvideos.core",
        "me.pinfort.tsvideos.console.common",
        "me.pinfort.tsvideos.manager",
    ],
)
@ConfigurationPropertiesScan(
    basePackages = [
        "me.pinfort.tsvideos.core.config",
        "me.pinfort.tsvideos.manager.infrastructure.config",
    ],
)
class ConsoleApplication

fun main(args: Array<String>) {
    ConsoleCharset.configureLogging()
    // Java's native stdout encoding can still follow the Windows console code page.
    // Set this before Spring/Clikt capture the stream so redirected JSON is UTF-8.
    if (args.any { it == "--json" || it == "--ids-only" || it == "cleanup" }) {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8))
    }
    if (DoctorBootstrap.commandArguments(args).firstOrNull() == "doctor") {
        exitProcess(DoctorBootstrap.run(args))
    }
    runApplication<ConsoleApplication>(*args)
}
