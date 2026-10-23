package me.pinfort.tsvideos.manager.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

// processing is not part of the manager applications (backend/docs/module-boundaries.md §2): neither scanned nor on
// the runtime classpath since #165, so no processing tool, processing bean or processor-* setting is loaded.
@SpringBootApplication(
    scanBasePackages = [
        "me.pinfort.tsvideos.storage",
        "me.pinfort.tsvideos.catalog",
        "me.pinfort.tsvideos.library",
        "me.pinfort.tsvideos.diagnostics",
        "me.pinfort.tsvideos.core",
        "me.pinfort.tsvideos.manager",
    ],
)
@ConfigurationPropertiesScan(
    basePackages = [
        "me.pinfort.tsvideos.manager.infrastructure.config",
    ],
)
class ApiApplication

fun main(args: Array<String>) {
    runApplication<ApiApplication>(*args)
}
