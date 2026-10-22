package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import me.pinfort.tsvideos.catalog.api.ExecutedFileCommand
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.manager.console.component.ProgramDetailToTextComponent
import me.pinfort.tsvideos.manager.console.component.ReadJson
import org.springframework.stereotype.Component

@Component
class Get(
    private val programCommand: ProgramCommand,
    private val executedFileCommand: ExecutedFileCommand,
    private val programDetailToTextComponent: ProgramDetailToTextComponent,
) : ReadCommand() {
    override fun help(context: Context): String = "get resources"

    private val resourceType: ResourceType by argument("resourceType", "type of target resource").enum<ResourceType>()
    private val id by argument("id", "id of resource").long().restrictTo(min = 1)

    enum class ResourceType {
        PROGRAM,
        EXECUTED_FILE,
    }

    override fun read() {
        if (idsOnly && resourceType == ResourceType.EXECUTED_FILE) {
            throw UsageError("--ids-only is available for program resources only")
        }
        when (resourceType) {
            ResourceType.PROGRAM -> {
                val program = programCommand.findDetail(id) ?: missing("program")
                when {
                    json -> outputJson("program", ReadJson.detail(program))
                    idsOnly -> echo(program.id)
                    else -> println(programDetailToTextComponent.convertConsole(program))
                }
            }
            ResourceType.EXECUTED_FILE -> {
                val executedFile = executedFileCommand.find(id) ?: missing("executedFile")
                if (json) outputJson("executedFile", ReadJson.executedFile(executedFile)) else println(executedFile)
            }
        }
    }
}
