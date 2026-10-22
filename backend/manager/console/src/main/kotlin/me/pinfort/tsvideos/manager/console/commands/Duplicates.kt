package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.console.common.component.TerminalTextColorComponent
import me.pinfort.tsvideos.core.command.DuplicateProgramCommand
import me.pinfort.tsvideos.manager.console.component.ReadJson
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@Component
class Duplicates(
    private val duplicateProgramCommand: DuplicateProgramCommand,
    private val programCommand: ProgramCommand,
    private val terminalTextColorComponent: TerminalTextColorComponent,
) : ReadCommand() {
    override fun help(context: Context): String = "detect programs recorded from the same broadcast"

    private val datetimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    private val minOverlapRatio by option(
        "--min-overlap",
        help = "minimum overlap ratio against the longer recording to treat as duplicate",
    ).double()
        .default(DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO)
        .check("must be greater than 0 and at most 1") { it > 0.0 && it <= 1.0 }

    override fun read() {
        val groups = duplicateProgramCommand.detect(minOverlapRatio)
        if (json) {
            outputJson(
                "groups",
                groups.map { group ->
                    linkedMapOf(
                        "reason" to group.reason.name,
                        "recommendedId" to group.recommended.id,
                        "candidateIds" to group.others.map { it.id },
                        "programs" to group.programs.map { ReadJson.program(it) + ("tsExists" to programCommand.hasTsFile(it)) },
                    )
                },
            )
            return
        }
        if (idsOnly) {
            groups
                .flatMap { it.programs }
                .map { it.id }
                .distinct()
                .sorted()
                .forEach { echo(it) }
            return
        }

        groups.forEach { group ->
            println("[${group.reason}] ${group.recommended.channelName}")
            println("keep\tid\trecorded_at\t\tduration\tdrops\tstatus\t\ttsExists\tname")
            group.programs.forEach {
                val keep = if (it == group.recommended) "*" else ""
                println(decorateProgramInfo(it.drops, "$keep\t${programInfo(it)}"))
            }
            println()
        }

        println("${groups.size} duplicate groups found")
        if (groups.isNotEmpty()) {
            println("delete candidates: ${groups.flatMap { it.others }.joinToString(" ") { it.id.toString() }}")
        }
    }

    private fun programInfo(program: Program): String =
        "%d\t%s\t%s\t\t%d\t%-10s\t%b\t\t%s".format(
            program.id,
            datetimeFormat.format(program.recordedAt),
            program.duration.seconds.toString(DurationUnit.MINUTES),
            program.drops,
            program.status,
            programCommand.hasTsFile(program),
            program.name,
        )

    private fun decorateProgramInfo(
        drops: Int,
        programInfo: String,
    ): String =
        when {
            drops > 0 -> terminalTextColorComponent.error(programInfo)
            else -> programInfo
        }
}
