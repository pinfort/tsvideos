package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.pinfort.tsvideos.console.common.component.TerminalTextColorComponent
import me.pinfort.tsvideos.core.command.ProgramCommand
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

@Component
class Search(
    private val programCommand: ProgramCommand,
    private val terminalTextColorComponent: TerminalTextColorComponent,
) : CliktCommand() {
    override fun help(context: Context): String = "search programs"

    private val datetimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    private val programSearchName by option("-n", "--name", help = "ambiguous program name for search").required()

    override fun run() {
        println("id\trecorded_at\t\tchannelName\t\tdrops\ttsExists\ttitle")

        val programs = programCommand.selectByName(programSearchName, 500, 0) // TODO: 全部JOINにしてSQL発行回数をなんとかする
        programs
            .sortedBy { it.recordedAt }
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
}
