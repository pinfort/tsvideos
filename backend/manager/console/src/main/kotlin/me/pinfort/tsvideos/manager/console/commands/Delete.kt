package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import me.pinfort.tsvideos.console.common.component.UserQuestionComponent
import me.pinfort.tsvideos.core.command.CreatedFileCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import org.springframework.stereotype.Component

@Component
class Delete(
    private val programCommand: ProgramCommand,
    private val userQuestionComponent: UserQuestionComponent,
    private val createdFileCommand: CreatedFileCommand,
) : CliktCommand() {
    override fun help(context: Context): String = "delete resources"

    private val targetType by argument("targetType", "type of target resource").enum<TargetType>()
    private val ids by argument("ids", "ids of resource to delete. if ts_files, program id.").int().multiple()
    private val dryRun by option("-d", "--dry-run").flag(default = false)

    enum class TargetType {
        PROGRAM,
        TS_FILES,
    }

    override fun run() {
        if (dryRun) {
            println("in dry run mode.")
        }

        ids.forEach {
            when (targetType) {
                TargetType.PROGRAM -> deleteProgram(it)
                TargetType.TS_FILES -> deleteTsFiles(it)
            }
        }
    }

    private fun deleteProgram(id: Int) {
        val targetProgram = programCommand.find(id.toLong()) ?: return println("program not found")

        println("program ready to delete:$targetProgram")
        val response = userQuestionComponent.askDefaultFalse("delete?")
        if (!response) {
            println("canceled")
            return
        }
        programCommand.delete(targetProgram, dryRun)
        println("program deleted")
    }

    private fun deleteTsFiles(id: Int) {
        val targetProgram = programCommand.findDetail(id.toLong()) ?: return println("program not found")

        val targetFiles = targetProgram.createdFiles.filter { it.isTs }

        println("created file ready to delete")
        targetFiles.forEach { println(it.file) }
        val response = userQuestionComponent.askDefaultFalse("delete?")
        if (!response) {
            println("canceled")
            return
        }
        targetFiles.forEach { createdFileCommand.delete(it, dryRun) }
        println("ts files deleted")
    }
}
