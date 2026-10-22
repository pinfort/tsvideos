package me.pinfort.tsvideos.catalog

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InjectionPoint
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Scope

/**
 * The per-injection-point `Logger` the applications define (core's LoggerConfig), for catalog's slice tests:
 * catalog only asks for a `Logger` and does not depend on who defines it.
 */
@TestConfiguration
class TestLoggerConfig {
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    fun logger(injectionPoint: InjectionPoint): Logger =
        LoggerFactory.getLogger(
            injectionPoint.methodParameter?.containingClass
                ?: injectionPoint.field?.declaringClass,
        )
}
