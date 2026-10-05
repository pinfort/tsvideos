package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.domain.SplittedFile
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Path

/**
 * 指定した録画ファイルに紐づく処理をリセットする。
 * executed_file をキーに program を辿り、生成物（splitted_file / created_file の各レコード、
 * NAS 上のファイル、ローカルに残った分割ファイル）を削除して program ごと消す。
 * 元の録画ファイル自体は削除しない。
 *
 * Python 版 reset.py / RemoveProgram の移植。
 * ProgramCommand.delete が行う DB・NAS の削除に加えて、tssplitter 直下および
 * Amatsukaze が移動した succeeded ディレクトリに残る分割ファイルも削除する。
 *
 * FileProcessingPipeline と異なりロールバックは行わない。
 * ただし取り返しのつかないローカルファイルの削除は、巻き戻せる DB・NAS の削除
 * （ProgramCommand.delete、失敗時はトランザクションでロールバックされる）が
 * 成功したあとに行う。途中で失敗しても再実行できる。
 */
@Component
class ResetRunner(
    private val executedFileCommand: ExecutedFileCommand,
    private val programCommand: ProgramCommand,
    private val splittedFileCommand: SplittedFileCommand,
    private val logger: Logger,
) {
    fun run(
        file: Path,
        dryRun: Boolean = false,
    ) {
        val target = file.toFile().absolutePath
        logger.info("resetting processing, file=$target, dryRun=$dryRun")

        val executedFile = executedFileCommand.findByFile(target)
        if (executedFile == null) {
            logger.error("executed file not found, aborting. file=$target")
            return
        }

        val program = programCommand.findByExecutedFileId(executedFile.id)
        if (program == null) {
            logger.error("program not found, aborting. executedFileId=${executedFile.id}")
            return
        }

        logger.info("program to be deleted, id=${program.id}, name=${program.name}")

        // splitted_file のローカルパスは行が消える前に控えておく
        val splittedFiles = splittedFileCommand.selectByExecutedFileId(executedFile.id)

        // DB・NAS の削除を先に行う。失敗時はトランザクションでロールバックされ、
        // ローカルファイルには手を付けていないので再実行できる。
        programCommand.delete(program, dryRun)

        deleteLocalSplittedFiles(splittedFiles, dryRun)

        logger.info("reset completed, file=$target, programId=${program.id}")
    }

    /**
     * ローカルに残った分割ファイルを削除する。
     * Amatsukaze が succeeded ディレクトリへ移動したコピーも合わせて削除する。
     */
    private fun deleteLocalSplittedFiles(
        splittedFiles: List<SplittedFile>,
        dryRun: Boolean,
    ) {
        splittedFiles.forEach { splittedFile ->
            val local = File(splittedFile.file)
            deleteLocalFile(local, dryRun)
            local.parentFile?.let { parent ->
                deleteLocalFile(File(File(parent, "succeeded"), local.name), dryRun)
            }
        }
    }

    private fun deleteLocalFile(
        file: File,
        dryRun: Boolean,
    ) {
        if (!file.exists()) return
        logger.info("removing local file, file=$file")
        if (dryRun) return
        if (!file.delete()) {
            logger.warn("failed to delete local file, file=$file")
        }
    }
}
