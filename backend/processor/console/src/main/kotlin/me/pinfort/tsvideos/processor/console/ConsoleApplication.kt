package me.pinfort.tsvideos.processor.console

import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

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
    runApplication<ConsoleApplication>(*args)
}
