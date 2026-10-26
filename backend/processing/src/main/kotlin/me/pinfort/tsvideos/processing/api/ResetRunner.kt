package me.pinfort.tsvideos.processing.api

import me.pinfort.tsvideos.catalog.api.ExecutedFileCommand
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.api.SplittedFileCommand
import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.processing.domain.ProcessingJob
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
 * NAS 操作は DB トランザクションで戻せない。失敗時は台帳と元録画を保持する。
 * program 削除後の台帳 cleanup 失敗は RESETTING job から再試行する。
 */
@Component
class ResetRunner(
    private val executedFileCommand: ExecutedFileCommand,
    private val programCommand: ProgramCommand,
    private val splittedFileCommand: SplittedFileCommand,
    private val logger: Logger,
    private val processingJobCommand: ProcessingJobCommand,
    private val verifiedUploadCommand: VerifiedUploadCommand,
) {
    fun run(
        file: Path,
        dryRun: Boolean = false,
    ) {
        val target = file.toFile().canonicalPath
        logger.info("resetting processing, file=$target, dryRun=$dryRun")

        // 064 より前の行は absolutePath で登録されているため、見つからなければそちらでも探す
        val executedFile =
            executedFileCommand.findByFile(target)
                ?: executedFileCommand.findByFile(file.toFile().absolutePath)
        if (executedFile == null) {
            if (finishInterruptedReset(target, dryRun)) return
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

        // NAS effects cannot be rolled back by the database; preserve local files and job evidence on failure.
        val job = processingJobCommand.findByExecutedFile(executedFile.id)
        if (!dryRun) processingJobCommand.prepareReset(executedFile.id)
        if (!dryRun && job != null) verifiedUploadCommand.validateReset(job.id)
        programCommand.delete(program, dryRun)

        if (!dryRun && job != null) verifiedUploadCommand.retireAfterReset(job.id)
        deleteLocalSplittedFiles(splittedFiles, dryRun)
        if (!dryRun) processingJobCommand.reset(executedFile.id)

        logger.info("reset completed, file=$target, programId=${program.id}")
    }

    private fun finishInterruptedReset(
        target: String,
        dryRun: Boolean,
    ): Boolean {
        val job = processingJobCommand.findByPath(target) ?: return false
        if (job.state != ProcessingJob.State.RESETTING) return false
        val executedId = checkNotNull(job.executedFileId) { "Reset job has no registration identity" }
        check(
            job.callbackClaim == null &&
                executedFileCommand.find(executedId) == null &&
                programCommand.findByExecutedFileId(executedId) == null &&
                splittedFileCommand.selectByExecutedFileId(executedId).isEmpty(),
        ) {
            "Partial reset still has related records; reconcile before finishing"
        }
        logger.warn("Finishing interrupted reset job ${job.id}; unrecorded local split paths are preserved for manual inspection")
        if (!dryRun) {
            verifiedUploadCommand.retireAfterReset(job.id)
            processingJobCommand.reset(executedId)
        }
        return true
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
