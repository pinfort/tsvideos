package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/** Presentation and failure contract for manager read commands. */
abstract class ReadCommand : CliktCommand() {
    protected val json by option("--json", help = "emit schema version 1 JSON").flag()
    protected val idsOnly by option("--ids-only", help = "emit program IDs, one per line").flag()

    final override fun run() {
        if (json && idsOnly) throw UsageError("--json and --ids-only are mutually exclusive")
        try {
            read()
        } catch (error: CliktError) {
            throw error
        } catch (error: Exception) {
            // Driver exceptions can contain credentials, connection strings or file contents,
            // so only the class name is logged (to stderr via logback), never the message.
            logger.error("Read failed: {}", error.javaClass.name)
            throw CliktError("Unable to read resources", statusCode = 1)
        }
    }

    protected abstract fun read()

    protected fun outputJson(
        key: String,
        value: Any?,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        echo(mapper.writeValueAsString(linkedMapOf("schemaVersion" to 1, key to value) + extra))
    }

    protected fun missing(resource: String): Nothing = throw CliktError("$resource not found", statusCode = 3)

    private companion object {
        val mapper: JsonMapper = JsonMapper.builder().build()
        val logger = LoggerFactory.getLogger(ReadCommand::class.java)
    }
}
