package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import me.pinfort.tsvideos.processing.api.AfterEncodeRunner
import me.pinfort.tsvideos.processor.console.display.ProgressPrinter
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Amatsukaze の実行後バッチから呼び出されるコマンド。
 * 各オプションは Amatsukaze が設定する環境変数から読むが、テスト用に明示指定もできる。
 */
@Component
class AfterEncodeCommand(
    private val afterEncodeRunner: AfterEncodeRunner,
) : CliktCommand(name = "after-encode") {
    companion object {
        private const val FILES_SEPARATOR = ";"
        private const val SUCCESS_VALUE = "1"
    }

    override fun help(context: Context): String = "register amatsukaze encoded files, upload them to the NAS and complete the program"

    private val itemId by option("--item-id", envvar = "ITEM_ID", help = "amatsukaze item id").int().required()
    private val inPath by option("--in-path", envvar = "IN_PATH", help = "encode input file").path().required()
    private val files by
        option("--files", envvar = "FILES", help = "encoded output files, separated by '$FILES_SEPARATOR'").default("")
    private val success by option("--success", envvar = "SUCCESS", help = "'$SUCCESS_VALUE' when the encode succeeded").default("")
    private val errorMessage by option("--error-message", envvar = "ERROR_MESSAGE", help = "reason of the encode failure").default("")
    private val dryRun by option("-d", "--dry-run").flag(default = false)

    private val uploadProgressPrinter = ProgressPrinter("Uploading")

    override fun run() {
        afterEncodeRunner.run(
            AfterEncodeRunner.Input(
                itemId = itemId,
                inPath = inPath,
                files =
                    files
                        .split(FILES_SEPARATOR)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { Path.of(it) },
                success = success == SUCCESS_VALUE,
                errorMessage = errorMessage,
            ),
            dryRun,
            uploadProgressPrinter::render,
        )
    }
}
