package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import me.pinfort.tsvideos.processor.console.component.UserQuestionComponent
import me.pinfort.tsvideos.processor.infrastructure.pipeline.ResetRunner
import org.springframework.stereotype.Component

/**
 * 指定した録画ファイルに紐づく処理をリセットする。
 * 生成されたファイル（分割ファイル・エンコード済みファイル・NAS 上のファイル）は
 * 軒並み削除され、program ごと消える。元の録画ファイルは残る。
 * Python 版 reset.py の移植。
 */
@Component
class ResetCommand(
    private val resetRunner: ResetRunner,
    private val userQuestionComponent: UserQuestionComponent,
) : CliktCommand(name = "reset") {
    override fun help(context: Context): String =
        "reset processing for a recording file: delete its program and every generated file (the original recording is kept)"

    private val file by argument("file", help = "the recorded file whose processing should be reset").path()
    private val dryRun by option("-d", "--dry-run").flag(default = false)

    override fun run() {
        if (dryRun) {
            println("in dry run mode.")
        }
        if (!userQuestionComponent.askDefaultFalse("reset processing for $file?")) {
            println("canceled")
            return
        }
        resetRunner.run(file, dryRun)
    }
}
