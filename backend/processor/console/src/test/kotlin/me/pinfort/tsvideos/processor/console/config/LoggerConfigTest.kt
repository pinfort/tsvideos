package me.pinfort.tsvideos.processor.console.config

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.processor.console.ConsoleApplication
import org.slf4j.Logger
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class FirstLoggerHolder(
    val logger: Logger,
)

class SecondLoggerHolder(
    val logger: Logger,
)

class LoggerConfigTest :
    ExpectSpec({
        context("logger") {
            expect("names the logger after each injection target") {
                AnnotationConfigApplicationContext()
                    .apply {
                        register(LoggerConfig::class.java, FirstLoggerHolder::class.java, SecondLoggerHolder::class.java)
                        refresh()
                    }.use { context ->
                        // シングルトンだと2つ目以降が1つ目のLoggerを使い回してしまう
                        context.getBean(FirstLoggerHolder::class.java).logger.name shouldBe FirstLoggerHolder::class.java.name
                        context.getBean(SecondLoggerHolder::class.java).logger.name shouldBe SecondLoggerHolder::class.java.name
                    }
            }

            // The modules only ask for a Logger; the application's scan is what supplies it (#139 removed core's scan).
            expect("is inside the application's component scan") {
                val packages = ConsoleApplication::class.java.getAnnotation(SpringBootApplication::class.java).scanBasePackages
                packages.any { LoggerConfig::class.java.packageName.startsWith(it) } shouldBe true
            }
        }
    })
