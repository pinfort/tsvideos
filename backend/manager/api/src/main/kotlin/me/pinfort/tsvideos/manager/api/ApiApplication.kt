package me.pinfort.tsvideos.manager.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

// processing (scan and configuration properties; mappers in MyBatisConfig) is here only while core's
// VerifiedUploadCommand needs its beans; #165 removes it from the manager applications (#164).
@SpringBootApplication(
    scanBasePackages = [
        "me.pinfort.tsvideos.storage",
        "me.pinfort.tsvideos.catalog",
        "me.pinfort.tsvideos.library",
        "me.pinfort.tsvideos.diagnostics",
        "me.pinfort.tsvideos.processing",
        "me.pinfort.tsvideos.core",
        "me.pinfort.tsvideos.manager",
    ],
)
@ConfigurationPropertiesScan(
    basePackages = [
        "me.pinfort.tsvideos.processing.api.config",
        "me.pinfort.tsvideos.manager.infrastructure.config",
    ],
)
class ApiApplication

fun main(args: Array<String>) {
    runApplication<ApiApplication>(*args)
}
