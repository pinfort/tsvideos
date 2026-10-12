package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import me.pinfort.tsvideos.console.common.component.TerminalTextColorComponent
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramSearchQuery
import me.pinfort.tsvideos.manager.console.component.ReadJson
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Component
class Search(
    private val programCommand: ProgramCommand,
    private val terminalTextColorComponent: TerminalTextColorComponent,
) : ReadCommand() {
    override fun help(context: Context): String = "search programs"

    private val datetimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    private val programSearchName by option("-n", "--name", help = "case-insensitive literal name substring")
    private val channel by option("--channel", help = "exact channel identifier")
    private val recordedFrom by option("--recorded-from", help = "inclusive ISO local date-time").convert { parseDate(it) }
    private val recordedTo by option("--recorded-to", help = "exclusive ISO local date-time").convert { parseDate(it) }
    private val status by option("--status").enum<Program.Status>()
    private val minDrops by option("--min-drops").int()
    private val maxDrops by option("--max-drops").int()
    private val fileType by option("--file-type", help = "available FILE_MOVED TS or encoded video").enum<ProgramSearchQuery.FileType>()
    private val sort by option("--sort")
        .choice(
            "recorded-at" to ProgramSearchQuery.Sort.RECORDED_AT,
            "duration" to ProgramSearchQuery.Sort.DURATION,
            "size" to ProgramSearchQuery.Sort.SIZE,
        ).default(ProgramSearchQuery.Sort.RECORDED_AT)
    private val direction by option("--direction").enum<ProgramSearchQuery.Direction>().default(ProgramSearchQuery.Direction.DESC)
    private val limit by option("--limit", help = "page size, 1..500").int().default(100)
    private val offset by option("--offset", help = "number of matching programs to skip").int().default(0)

    private fun parseDate(value: String): LocalDateTime =
        try {
            LocalDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            throw UsageError("use an ISO local date-time, e.g. 2026-10-12T00:00:00")
        }

    override fun read() {
        val query =
            try {
                ProgramSearchQuery(
                    programSearchName,
                    channel,
                    recordedFrom,
                    recordedTo,
                    status,
                    minDrops,
                    maxDrops,
                    fileType,
                    sort,
                    direction,
                    limit,
                    offset,
                )
            } catch (error: IllegalArgumentException) {
                throw UsageError(error.message)
            }
        val page = programCommand.search(query)
        val programs = page.items
        if (json) {
            outputJson(
                "programs",
                programs.map { ReadJson.program(it) + ("tsExists" to programCommand.hasTsFile(it)) },
                linkedMapOf("total" to page.total, "limit" to page.limit, "offset" to page.offset),
            )
            return
        }
        if (idsOnly) {
            programs.forEach { echo(it.id) }
            return
        }
        println("id\trecorded_at\t\tchannelName\t\tdrops\ttsExists\ttitle")
        programs
            .forEach {
                val tsExists = programCommand.hasTsFile(it)
                val programInfo =
                    "%d\t%s\t%s\t%d\t%b\t%s".format(
                        it.id,
                        datetimeFormat.format(it.recordedAt),
                        it.channelName,
                        it.drops,
                        tsExists,
                        it.title,
                    )
                val decoratedProgramInfo = decorateProgramInfo(it.drops, programInfo)
                println(decoratedProgramInfo)
            }
        println("${programs.size} of ${page.total} programs (limit=${page.limit}, offset=${page.offset})")
    }

    private fun decorateProgramInfo(
        drops: Int,
        programInfo: String,
    ): String =
        when {
            drops == 0 -> programInfo
            drops > 0 -> terminalTextColorComponent.error(programInfo)
            else -> terminalTextColorComponent.info(programInfo)
        }
}
