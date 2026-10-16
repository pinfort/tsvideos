package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import me.pinfort.tsvideos.core.command.LibraryStatsCommand
import me.pinfort.tsvideos.core.domain.LibraryStatsQuery
import me.pinfort.tsvideos.core.domain.LibraryStatsTotals
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.Locale

@Component
class Stats(
    private val command: LibraryStatsCommand,
) : ReadCommand() {
    override fun help(context: Context): String = "summarize registered library metadata (no NAS scan)"

    private val groupBy by option("--group-by", help = "CHANNEL or MONTH").convert {
        try {
            LibraryStatsQuery.Group.valueOf(it.uppercase(Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            throw UsageError("--group-by must be CHANNEL or MONTH")
        }
    }
    private val recordedFrom by option(
        "--recorded-from",
        help = "inclusive strict ISO local date or date-time, e.g. 2026-01-01 or 2026-01-01T00:00:00",
    ).convert {
        date(it)
    }
    private val recordedTo by option(
        "--recorded-to",
        help = "exclusive strict ISO local date or date-time; a date means its midnight",
    ).convert {
        date(it)
    }

    override fun read() {
        if (idsOnly) throw UsageError("stats does not support --ids-only")
        val from = recordedFrom
        val to = recordedTo
        if (from != null && to != null && from >= to) throw UsageError("--recorded-from must precede --recorded-to")
        val query = LibraryStatsQuery(groupBy, from, to)
        val stats = command.summarize(query)
        if (json) {
            outputJson(
                "stats",
                linkedMapOf(
                    "groupBy" to groupBy?.name,
                    "recordedFrom" to recordedFrom?.toString(),
                    "recordedTo" to recordedTo?.toString(),
                    "totals" to stats.totals,
                    "groups" to stats.groups,
                ),
            )
        } else {
            echo("Registered metadata totals (bytes); physical usage: unverified")
            printTotals("TOTAL", stats.totals)
            stats.groups.forEach { printTotals(it.key ?: "(unknown)", it.totals) }
        }
    }

    private fun printTotals(
        label: String,
        totals: LibraryStatsTotals,
    ) {
        echo(
            "$label: programs=${totals.programCount}, failed=${totals.failedProgramCount}, " +
                "drops=${totals.droppedFrameCount}, unknownDrops=${totals.unknownDropsProgramCount}",
        )
        echo(
            "  files=${totals.registeredFileCount}, bytes=${totals.registeredBytes}, " +
                "originalTs=${totals.originalTsBytes}, encoded=${totals.encodedBytes}, other=${totals.otherBytes}",
        )
        echo("  moved=${totals.movedFileBytes}, pending=${totals.pendingFileBytes}, verifiedPhysical=unverified")
    }

    private fun date(value: String): LocalDateTime =
        try {
            if (value.contains('T')) LocalDateTime.parse(value) else LocalDate.parse(value).atStartOfDay()
        } catch (_: DateTimeParseException) {
            throw UsageError("Recording time must be an ISO local date or date-time without offset, e.g. 2026-01-01 or 2026-01-01T00:00:00")
        }
}
