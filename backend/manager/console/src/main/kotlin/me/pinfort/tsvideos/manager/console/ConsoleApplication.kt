package me.pinfort.tsvideos.manager.console

import me.pinfort.tsvideos.catalog.api.CatalogPersistence
import me.pinfort.tsvideos.console.common.charset.ConsoleCharset
import me.pinfort.tsvideos.library.api.LibraryPersistence
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

// processing is not part of the manager applications (backend/docs/module-boundaries.md §2): neither scanned nor on
// the runtime classpath since #165, so no processing tool, processing bean or processor-* setting is loaded.
// The per-injection-point Logger (config/LoggerConfig) and the DataSource settings (application-datasource.yaml) are
// this application's own since #139; storage registers its samba.* settings itself.
@MapperScan(
    basePackages = [
        CatalogPersistence.MAPPER_PACKAGE,
        LibraryPersistence.MAPPER_PACKAGE,
    ],
)
@SpringBootApplication(
    scanBasePackages = [
        "me.pinfort.tsvideos.storage",
        "me.pinfort.tsvideos.catalog",
        "me.pinfort.tsvideos.library",
        "me.pinfort.tsvideos.diagnostics",
        "me.pinfort.tsvideos.console.common",
        "me.pinfort.tsvideos.manager",
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
