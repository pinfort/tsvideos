package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.pinfort.tsvideos.console.common.component.TerminalTextColorComponent
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.manager.console.component.ReadJson
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

@Component
class Search(
    private val programCommand: ProgramCommand,
    private val terminalTextColorComponent: TerminalTextColorComponent,
) : ReadCommand() {
    override fun help(context: Context): String = "search programs"

    private val datetimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    private val programSearchName by option("-n", "--name", help = "ambiguous program name for search").required()

    override fun read() {
        // One extra row tells whether anything was cut off at the limit.
        val fetched = programCommand.selectByName(programSearchName, LIMIT + 1, 0) // TODO: 全部JOINにしてSQL発行回数をなんとかする
        val truncated = fetched.size > LIMIT
        val sorted = fetched.take(LIMIT).sortedWith(compareBy({ it.recordedAt }, { it.id }))
        if (truncated) echo("warning: more than $LIMIT programs matched; output is truncated", err = true)
        if (json) {
            outputJson(
                "programs",
                sorted.map { ReadJson.program(it) + ("tsExists" to programCommand.hasTsFile(it)) },
                mapOf("truncated" to truncated),
            )
            return
        }
        if (idsOnly) {
            sorted.forEach { echo(it.id) }
            return
        }
        println("id\trecorded_at\t\tchannelName\t\tdrops\ttsExists\ttitle")
        sorted
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

    companion object {
        const val LIMIT = 500
    }
}
