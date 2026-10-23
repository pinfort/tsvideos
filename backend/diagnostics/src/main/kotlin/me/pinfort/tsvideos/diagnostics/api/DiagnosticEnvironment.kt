package me.pinfort.tsvideos.diagnostics.api

import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.env.StandardEnvironment

/** Config data only: does not instantiate a datasource, tools or the application command graph. */
object DiagnosticEnvironment {
    fun commandArguments(args: Array<String>): List<String> {
        val (options, operands) = splitAtSeparator(args)
        return options.filterNot(::isSpringProperty) + operands
    }

    fun load(args: Array<String>): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(
                SimpleCommandLinePropertySource(*splitAtSeparator(args).first.filter(::isSpringProperty).toTypedArray()),
            )
            ConfigDataEnvironmentPostProcessor.applyTo(this)
        }

    private fun isSpringProperty(arg: String) = arg.startsWith("--spring.") && '=' in arg

    /** Arguments after `--` are operands (e.g. paths), never Spring properties. */
    private fun splitAtSeparator(args: Array<String>): Pair<List<String>, List<String>> {
        val separator = args.indexOf("--")
        return if (separator < 0) args.toList() to emptyList() else args.take(separator) to args.drop(separator)
    }
}
