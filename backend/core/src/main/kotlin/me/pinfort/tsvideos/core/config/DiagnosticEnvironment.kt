package me.pinfort.tsvideos.core.config

import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.env.StandardEnvironment

/** Config data only: does not instantiate a datasource, tools or the application command graph. */
object DiagnosticEnvironment {
    fun commandArguments(args: Array<String>): List<String> = args.filterNot { it.startsWith("--spring.") && '=' in it }

    fun load(args: Array<String>): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(
                SimpleCommandLinePropertySource(*args.filter { it.startsWith("--spring.") && '=' in it }.toTypedArray()),
            )
            ConfigDataEnvironmentPostProcessor.applyTo(this)
        }
}
