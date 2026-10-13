package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import me.pinfort.tsvideos.core.command.InventoryExportCommand
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramSearchQuery
import me.pinfort.tsvideos.manager.console.component.InventoryFormat
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class Export(
    private val inventory: InventoryExportCommand,
) : ReadCommand() {
    override fun help(context: Context): String = "Export complete metadata and file inventory without a result cap"

    private val ids by option("--id", help = "program ID, repeatable; cannot combine with filters").long().multiple()
    private val format by option("--format").choice("json", "csv").default("json")
    private val searchName by option("--name")
    private val channel by option("--channel")
    private val from by option("--recorded-from").convert { parseDate(it) }
    private val to by option("--recorded-to").convert { parseDate(it) }
    private val status by option("--status").enum<Program.Status>()
    private val minDrops by option("--min-drops").int()
    private val maxDrops by option("--max-drops").int()
    private val fileType by option("--file-type").enum<ProgramSearchQuery.FileType>()

    private fun parseDate(value: String): LocalDateTime =
        try {
            LocalDateTime.parse(value)
        } catch (_: Exception) {
            throw UsageError("Use ISO local date-time")
        }

    override fun read() {
        if (idsOnly || (json && format != "json")) throw UsageError("Export supports JSON or CSV, not IDs-only or conflicting formats")
        val query =
            try {
                ProgramSearchQuery(searchName, channel, from, to, status, minDrops, maxDrops, fileType)
            } catch (
                error: IllegalArgumentException,
            ) {
                throw UsageError(error.message)
            }
        if (ids.any { it <= 0 } ||
            (ids.isNotEmpty() && query != ProgramSearchQuery())
        ) {
            throw UsageError("Choose positive IDs or search filters")
        }
        val result = inventory.export(ids, query)
        if (result.missingIds.isNotEmpty()) missing("Programs ${result.missingIds.joinToString(",")}")
        // Materialize before stdout, so failed reads/serialization never masquerade as a complete export.
        if (format == "csv") {
            echo(InventoryFormat.csv(result), trailingNewline = false)
        } else {
            outputJson("export", InventoryFormat.json(result))
        }
    }
}
