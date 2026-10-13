package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ProcessingHistoryCommand
import me.pinfort.tsvideos.core.command.ProcessingJobCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.command.VerifiedUploadCommand
import me.pinfort.tsvideos.core.component.MimeTypeComponent
import me.pinfort.tsvideos.core.component.NasDestinationResolver
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.component.ValidateCompletedComponent
import me.pinfort.tsvideos.core.domain.ProcessingArtifact
import me.pinfort.tsvideos.core.domain.ProcessingAttempt
import me.pinfort.tsvideos.core.domain.ProcessingAttemptKind
import me.pinfort.tsvideos.core.domain.ProcessingErrorCode
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.ProcessingOutcome
import me.pinfort.tsvideos.core.domain.ProcessingStage
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.SplittedFile
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.processor.infrastructure.external.slack.SlackClient
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Path

/**
 * Amatsukaze のエンコード実行後バッチから呼び出される処理。
 * エンコード済みファイルを NAS へ検証付きでアップロードし、created_file と台帳へ登録してから
 * 元ファイルを削除し、番組を COMPLETED にする。
 *
 * FileProcessingPipeline と異なりロールバックは行わない。
 * NAS へのアップロードとローカルファイルの削除が済んだ後で失敗を巻き戻すことはできないため、
 * 失敗時はログと Slack 通知を行い番組を ERROR にするだけに留める。
 */
@Component
class AfterEncodeRunner(
    private val splittedFileCommand: SplittedFileCommand,
    private val executedFileCommand: ExecutedFileCommand,
    private val programCommand: ProgramCommand,
    private val nasDestinationResolver: NasDestinationResolver,
    private val mimeTypeComponent: MimeTypeComponent,
    private val validateCompletedComponent: ValidateCompletedComponent,
    private val slackClient: SlackClient,
    private val logger: Logger,
    private val processingJobCommand: ProcessingJobCommand,
    private val verifiedUploadCommand: VerifiedUploadCommand,
    private val history: ProcessingHistoryCommand,
    private val cancellation: ProcessingCancellation = ProcessingCancellation(),
) {
    /**
     * Amatsukaze が実行後バッチに渡す環境変数のうち、この処理で使うもの。
     *
     * @param itemId Amatsukaze のアイテムID
     * @param inPath 入力ファイルパス（Amatsukaze により succeeded ディレクトリへ移動済み）
     * @param files 出力ファイル群
     * @param success エンコードの成否
     * @param errorMessage エラーメッセージ（失敗したときのみ）
     */
    data class Input(
        val itemId: Int,
        val inPath: Path,
        val files: List<Path>,
        val success: Boolean,
        val errorMessage: String,
    )

    fun run(
        input: Input,
        dryRun: Boolean = false,
        onUploadProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        cancellation.check()
        if (!input.success) {
            val failure = IllegalStateException("Encoder reported failure")
            reportSafely(failure, {
                if (!dryRun) {
                    val failedSplit = findOriginalSplittedFile(input.inPath)
                    val failedJob = failedSplit?.let { processingJobCommand.findByExecutedFile(it.executedFileId) }
                    if (failedJob != null) {
                        val failedAttempt = history.begin(failedJob, ProcessingAttemptKind.CALLBACK)
                        val event = history.startStage(failedAttempt.id, ProcessingStage.FINALIZE)
                        history.finishStage(event, ProcessingOutcome.FAILED, ProcessingErrorCode.ENCODER_FAILURE)
                        history.finish(failedAttempt.id, ProcessingOutcome.FAILED, ProcessingErrorCode.ENCODER_FAILURE)
                    }
                }
            }, { logger.error("Encoder reported failure, itemId=${input.itemId}") }, {
                if (!dryRun) notifyError(input)
            })
            throw failure
        }

        val splittedFile = findOriginalSplittedFile(input.inPath)
        if (splittedFile == null) {
            val failure = IllegalStateException("Original split record missing")
            reportSafely(failure, { logger.error("Original split record missing") }, {
                if (!dryRun) slackClient.notify("original splitted file not found. in_path:${input.inPath}")
            })
            throw failure
        }

        val job = processingJobCommand.findByExecutedFile(splittedFile.executedFileId)
        check(job != null || dryRun) { "Legacy callback requires reconciliation before processing" }
        val token = if (!dryRun) processingJobCommand.claimCallback(checkNotNull(job)) else null
        check(dryRun || token != null) { "Callback already claimed; reconcile interrupted callback before retrying" }
        var attempt: ProcessingAttempt? = null
        var primaryFailure: Exception? = null
        try {
            if (!dryRun) attempt = history.begin(checkNotNull(job), ProcessingAttemptKind.CALLBACK)
            require(input.files.isNotEmpty()) { "Callback output list is empty" }
            val paths = input.files.map { it.toFile().canonicalPath }.sorted()
            require(paths.distinct().size == paths.size) { "Duplicate callback output paths" }
            if (job != null && token != null) {
                processingJobCommand.freezeManifest(
                    job,
                    token,
                    ProcessingIdentity.hash("${input.itemId}\u0000${paths.joinToString("\u0000")}"),
                )
            }
            if (dryRun) {
                logger.info("Dry run: would verify, register and remove callback outputs: ${input.files}")
            } else {
                input.files.forEach { path ->
                    cancellation.check()
                    val mime = mimeTypeComponent.guess(path.fileName.toString())
                    val active = checkNotNull(attempt)
                    val target = nasTargetFile(path)
                    val receipt =
                        history.stage(
                            active.id,
                            ProcessingStage.UPLOAD_ENCODED,
                            { ProcessingArtifact("UPLOAD_MANIFEST", it.manifestId, target) },
                        ) {
                            val uploaded =
                                verifiedUploadCommand.upload(
                                    checkNotNull(job).id,
                                    "encoded:${path.toFile().canonicalPath}",
                                    path.toFile(),
                                    SambaClient.NasType.VIDEO_STORE_NAS,
                                    target,
                                    history.progress(active.id, onUploadProgress),
                                )
                            verifiedUploadCommand.register(uploaded, splittedFile.id, mime.mime, mime.encoding)
                            uploaded
                        }
                    history.stage(active.id, ProcessingStage.FINALIZE) { verifiedUploadCommand.deleteLocal(receipt) }
                }
            }
            if (attempt == null) {
                finishProcess(splittedFile.executedFileId, input.inPath, dryRun)
            } else {
                history.stage(attempt.id, ProcessingStage.FINALIZE, { ProcessingArtifact("PROGRAM", it, null) }) {
                    finishProcess(splittedFile.executedFileId, input.inPath, dryRun)
                }
                history.finish(attempt.id, ProcessingOutcome.COMPLETED)
            }
        } catch (e: Exception) {
            primaryFailure = e
            if (attempt != null) {
                try {
                    history.failed(attempt.id, e)
                } catch (historyFailure: Exception) {
                    e.addSuppressed(historyFailure)
                    if (ProcessingCancellation.isInterrupted(historyFailure)) Thread.currentThread().interrupt()
                }
            }
            if (ProcessingCancellation.isInterrupted(e)) throw e
            // Report once, then propagate failure so unattended callers receive a nonzero exit.
            if (job?.state == "COMPLETED") {
                logger.error("Rejected callback for completed job ${job.id}; existing outcome preserved", e)
                throw e
            }
            notifyFailure(
                splittedFile.executedFileId,
                "registering or uploading encoded files failed. in_path:${input.inPath}",
                e,
                dryRun,
            )
            throw e
        } finally {
            if (job != null && token != null) {
                try {
                    ProcessingCancellation.recovery { processingJobCommand.releaseCallback(job, token) }
                } catch (releaseFailure: Exception) {
                    if (primaryFailure == null) throw releaseFailure
                    primaryFailure.addSuppressed(releaseFailure)
                    if (ProcessingCancellation.isInterrupted(releaseFailure)) Thread.currentThread().interrupt()
                }
            }
        }
        logger.info("uploading file completed, targets=${input.files}")
    }

    private fun notifyError(input: Input) {
        logger.error("encode failed, amatsukazeId=${input.itemId}, inPath=${input.inPath}, reason=${input.errorMessage}")
        slackClient.notify(
            "encode failed.\namatsukaze_id:${input.itemId},\nin_path:${input.inPath},\n`reason:${input.errorMessage}`",
        )
    }

    /**
     * Amatsukaze が入力ファイルを succeeded ディレクトリへ移動しているため、
     * 1つ上の階層に元のパスを組み立て直して splitted_file を探す。
     */
    private fun findOriginalSplittedFile(inPath: Path): SplittedFile? {
        val originalFile = inPath.parent.parent.resolve(inPath.fileName)
        return splittedFileCommand.findByFile(originalFile.toFile().canonicalPath)
    }

    private fun nasTargetFile(file: Path): String =
        nasDestinationResolver.resolve(
            file.parent.parent.parent,
            file.fileName.toString(),
            SambaClient.NasType.VIDEO_STORE_NAS,
        )

    private fun finishProcess(
        executedFileId: Long,
        inPath: Path,
        dryRun: Boolean,
    ): Long {
        val program = programCommand.findByExecutedFileId(executedFileId) ?: error("program not found")
        check(validateCompletedComponent.validate(program.id)) { "program invalid" }
        if (!dryRun) {
            val job = checkNotNull(processingJobCommand.findByExecutedFile(executedFileId))
            verifiedUploadCommand.verifyRegistered(job.id, "original")
            val raw = File(job.inputPath)
            if (raw.exists()) check(ProcessingIdentity.from(raw).key == job.identityKey) { "Original recording changed; preserved" }
        }
        deleteLocalFile(inPath.toFile(), dryRun)
        removeExecutedFile(executedFileId, dryRun)
        programCommand.updateStatusByExecutedFileId(executedFileId, Program.Status.COMPLETED, dryRun)
        if (!dryRun) processingJobCommand.findByExecutedFile(executedFileId)?.let { processingJobCommand.complete(it) }
        return program.id
    }

    /**
     * ロールバックできない失敗を、ログ・Slack通知・番組の ERROR 化で報告する。
     */
    private fun notifyFailure(
        executedFileId: Long,
        message: String,
        e: Exception,
        dryRun: Boolean,
    ) {
        reportSafely(
            e,
            { logger.error(message, e) },
            { if (!dryRun) slackClient.notify("$message, e:${e.message}\nstackTrace:\n```\n${e.stackTraceToString()}\n```") },
            { programCommand.updateStatusByExecutedFileId(executedFileId, Program.Status.ERROR, dryRun) },
        )
    }

    private fun reportSafely(
        failure: Exception,
        vararg reports: () -> Unit,
    ) {
        reports.forEach { report ->
            try {
                ProcessingCancellation.recovery(report)
            } catch (reportFailure: Exception) {
                failure.addSuppressed(reportFailure)
                if (ProcessingCancellation.isInterrupted(reportFailure)) Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * TSファイル一連の処理の元ファイルを削除する。DBのレコードは残す。
     */
    private fun removeExecutedFile(
        executedFileId: Long,
        dryRun: Boolean,
    ) {
        logger.info("executed file to be deleted, id=$executedFileId")
        val executedFile = executedFileCommand.find(executedFileId)
        if (executedFile == null) {
            logger.warn("executed file not found, id=$executedFileId")
            return
        }
        deleteLocalFile(File(executedFile.file), dryRun)
        splittedFileCommand.selectByExecutedFileId(executedFileId).forEach {
            deleteLocalFile(File(it.file), dryRun)
        }
    }

    private fun deleteLocalFile(
        file: File,
        dryRun: Boolean,
    ) {
        cancellation.check()
        if (!file.exists()) return
        logger.info("removing file, file=$file")
        if (!dryRun) {
            java.nio.file.Files
                .delete(file.toPath())
        }
    }
}
