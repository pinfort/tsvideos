package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import me.pinfort.tsvideos.console.common.component.UserQuestionComponent
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.component.DirectoryNameComponent
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class Modify(
    private val programCommand: ProgramCommand,
    private val directoryNameComponent: DirectoryNameComponent,
    private val userQuestionComponent: UserQuestionComponent,
) : CliktCommand() {
    override fun help(context: Context): String = "modify programs"

    private val id by argument("id", "id of resource").int()
    private val targetType by argument("targetType", "type of target resource").enum<TargetType>()
    private val newValue by argument("newValue", "new value of target resource")
    private val dryRun by option("-d", "--dry-run").flag(default = false)

    enum class TargetType {
        DIRECTORY_NAME,
    }

    override fun run() {
        when (targetType) {
            TargetType.DIRECTORY_NAME -> {
                val program = programCommand.find(id.toLong()) ?: return println("program not found")
                println("You are moving files in program id: ${program.id}, name:${program.name}")
                val createdFiles = programCommand.videoFiles(program)
                createdFiles.forEach {
                    val oldPath = Path.of(it.file.replace('\\', '/'))
                    val newPath = directoryNameComponent.replaceWithGivenDirectoryName(oldPath, newValue).toString().replace('/', '\\')
                    println("mv ${oldPath.toString().replace('/', '\\')} to $newPath")
                }
                val answer = userQuestionComponent.askDefaultFalse("Are you sure to move files?")
                if (!answer) {
                    println("canceled")
                    return
                }
                programCommand.moveCreatedFiles(program, newValue, dryRun)
                println("moved")
            }
        }
    }
}
