package me.pinfort.tsvideos.manager.console

import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@MapperScan("me.pinfort.tsvideos.core.external.database.mapper")
@SpringBootApplication(
    scanBasePackages = [
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
    runApplication<ConsoleApplication>(*args)
}
