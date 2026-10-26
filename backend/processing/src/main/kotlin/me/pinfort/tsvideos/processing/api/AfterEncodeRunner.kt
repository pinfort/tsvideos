package me.pinfort.tsvideos.processing.api

import me.pinfort.tsvideos.catalog.api.ExecutedFileCommand
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.api.SplittedFileCommand
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.processing.api.tool.ChapterProbeClient
import me.pinfort.tsvideos.processing.api.tool.VideoResolutionProbeClient
import me.pinfort.tsvideos.processing.domain.ProcessingArtifact
import me.pinfort.tsvideos.processing.domain.ProcessingArtifactType
import me.pinfort.tsvideos.processing.domain.ProcessingAttempt
import me.pinfort.tsvideos.processing.domain.ProcessingAttemptKind
import me.pinfort.tsvideos.processing.domain.ProcessingErrorCode
import me.pinfort.tsvideos.processing.domain.ProcessingIdentity
import me.pinfort.tsvideos.processing.domain.ProcessingJob
import me.pinfort.tsvideos.processing.domain.ProcessingOutcome
import me.pinfort.tsvideos.processing.domain.ProcessingStage
import me.pinfort.tsvideos.processing.external.slack.SlackClient
import me.pinfort.tsvideos.storage.api.NasStore
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Amatsukaze のエンコード実行後バッチから呼び出される処理。
 * エンコード済みファイルを NAS へ検証付きでアップロードし、created_file と台帳へ登録してから
 * 元ファイルを削除し、番組を COMPLETED にする。
 *
 * FileProcessingPipeline と異なりロールバックは行わない。
 * NAS へのアップロードとローカルファイルの削除が済んだ後で失敗を巻き戻すことはできないため、
 * 失敗時はログと Slack 通知を行い番組を ERROR にするだけに留める。
 *
 * コンストラクタは internal (Slack クライアントが processing の内部)。
 */
@Component
class AfterEncodeRunner internal constructor(
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
    private val videoResolutionProbeClient: VideoResolutionProbeClient,
    private val chapterProbeClient: ChapterProbeClient,
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
                if (!dryRun) recordEncoderFailure(input)
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

        val (job, token) =
            try {
                val job = processingJobCommand.findByExecutedFile(splittedFile.executedFileId)
                check(job != null || dryRun) { "Legacy callback requires reconciliation before processing" }
                val token = if (!dryRun) processingJobCommand.claimCallback(checkNotNull(job)) else null
                check(dryRun || token != null) {
                    "Callback of job ${job?.id} already claimed by ${job?.callbackClaim} (last updated ${job?.updatedAt} UTC); " +
                        "reconcile an interrupted callback before retrying"
                }
                job to token
            } catch (e: Exception) {
                // 別のコールバックが処理中の可能性があるため、番組の状態は変えずに通知だけ行う
                report("claiming encode callback failed. in_path:${input.inPath}", e, dryRun)
                throw e
            }
        // claim 前のスナップショット。この呼び出し内で完了した後の失敗は履歴の記録だけなので、catch で completed として扱う。
        val alreadyCompleted = job?.state == ProcessingJob.State.COMPLETED
        var completed = false
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
            input.files.forEach { path ->
                cancellation.check()
                val mime = mimeTypeComponent.guess(path.fileName.toString())
                val target = nasTargetFile(path)
                if (dryRun) {
                    check(path.toFile().isFile) { "Callback output missing: $path" }
                    logger.info("Dry run: would verify, register and remove callback output $path as $target (${mime.mime})")
                    return@forEach
                }
                val active = checkNotNull(attempt)
                val receipt =
                    history.stage(
                        active.id,
                        ProcessingStage.UPLOAD_ENCODED,
                        { ProcessingArtifact(ProcessingArtifactType.UPLOAD_MANIFEST, it.manifestId, target) },
                    ) {
                        val uploaded =
                            verifiedUploadCommand.upload(
                                checkNotNull(job).id,
                                "encoded:${path.toFile().canonicalPath}",
                                path.toFile(),
                                NasStore.VIDEO_STORE_NAS,
                                target,
                                history.progress(active.id, onUploadProgress),
                            )
                        // Probe the local file: it is byte-identical to the verified upload and avoids a NAS round trip.
                        // Subtitles, compressed files and other non-video outputs have no readable resolution.
                        val resolution = if (mime.isProbeableVideo) videoResolutionProbeClient.probe(path.toFile()) else null
                        // Chapters are best-effort too: a failed probe (null) registers the file as not probed, for the
                        // backfill command to retry; only an interruption stops the callback.
                        val chapters = if (mime.isProbeableVideo) chapterProbeClient.probe(path.toFile()) else null
                        verifiedUploadCommand.register(uploaded, splittedFile.id, mime.mime, mime.encoding, resolution, chapters)
                        uploaded
                    }
                history.stage(active.id, ProcessingStage.FINALIZE) { verifiedUploadCommand.deleteLocal(receipt) }
            }
            if (attempt == null) {
                finishProcess(splittedFile.executedFileId, input.inPath, dryRun)
            } else {
                history.stage(attempt.id, ProcessingStage.FINALIZE, { ProcessingArtifact(ProcessingArtifactType.PROGRAM, it, null) }) {
                    finishProcess(splittedFile.executedFileId, input.inPath, dryRun).also { completed = true }
                }
                history.finish(attempt.id, ProcessingOutcome.COMPLETED)
            }
            completed = true
        } catch (e: Exception) {
            if (completed) {
                // The program is already COMPLETED and its files are gone; only the history write failed.
                report("recording completed callback history failed; program stays COMPLETED. in_path:${input.inPath}", e, dryRun)
                return
            }
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
            if (alreadyCompleted) {
                logger.error("Rejected callback for completed job ${job?.id}; existing outcome preserved", e)
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
                    if (primaryFailure == null) {
                        report("releasing callback claim of job ${job.id} failed; reconcile the stale claim $token", releaseFailure)
                        throw releaseFailure
                    }
                    primaryFailure.addSuppressed(releaseFailure)
                    if (ProcessingCancellation.isInterrupted(releaseFailure)) Thread.currentThread().interrupt()
                }
            }
        }
        if (completed) logger.info("uploading file completed, targets=${input.files}")
    }

    /** Best effort: Slack is the only signal from this unattended batch, so history must not block notifyError. */
    private fun recordEncoderFailure(input: Input) {
        try {
            val failedSplit = findOriginalSplittedFile(input.inPath)
            val failedJob = failedSplit?.let { processingJobCommand.findByExecutedFile(it.executedFileId) } ?: return
            val failedAttempt = history.begin(failedJob, ProcessingAttemptKind.CALLBACK)
            val event = history.startStage(failedAttempt.id, ProcessingStage.FINALIZE)
            history.finishStage(event, ProcessingOutcome.FAILED, ProcessingErrorCode.ENCODER_FAILURE)
            history.finish(failedAttempt.id, ProcessingOutcome.FAILED, ProcessingErrorCode.ENCODER_FAILURE)
        } catch (e: Exception) {
            logger.error("recording encoder failure history failed, inPath=${input.inPath}", e)
        }
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
        val originalFile =
            inPath.parent.parent
                .resolve(inPath.fileName)
                .toFile()
        // 064 より前の行は absolutePath で登録されているため、見つからなければそちらでも探す
        return splittedFileCommand.findByFile(originalFile.canonicalPath)
            ?: splittedFileCommand.findByFile(originalFile.absolutePath)
    }

    private fun nasTargetFile(file: Path): String =
        nasDestinationResolver.resolve(
            file.parent.parent.parent,
            file.fileName.toString(),
            NasStore.VIDEO_STORE_NAS,
        )

    /** 番組を COMPLETED にして番組IDを返す。失敗は例外として投げ、呼び出し元で履歴に記録してから通知する。 */
    private fun finishProcess(
        executedFileId: Long,
        inPath: Path,
        dryRun: Boolean,
    ): Long {
        val program = programCommand.findByExecutedFileId(executedFileId) ?: error("program not found. executed_file_id:$executedFileId")
        check(validateCompletedComponent.validate(program.id)) { "program invalid. programId:${program.id}" }
        if (!dryRun) {
            val job = checkNotNull(processingJobCommand.findByExecutedFile(executedFileId))
            verifiedUploadCommand.verifyRegistered(job.id, "original")
            val raw = File(job.inputPath)
            if (raw.exists()) check(ProcessingIdentity.from(raw).key == job.identityKey) { "Original recording changed; preserved" }
        }
        deleteLocalFile(inPath.toFile(), dryRun)
        removeExecutedFile(executedFileId, dryRun)
        programCommand.updateStatusByExecutedFileId(executedFileId, Program.Status.COMPLETED, dryRun)
        if (!dryRun) completeJob(executedFileId)
        return program.id
    }

    /**
     * 番組はすでに COMPLETED なので、ジョブの完了記録に失敗しても番組を ERROR にはしない。
     * ジョブは SUBMITTED / SUBMISSION_UNKNOWN のまま残り、コールバックを再実行すれば完了する。
     */
    private fun completeJob(executedFileId: Long) {
        try {
            processingJobCommand.findByExecutedFile(executedFileId)?.let { processingJobCommand.complete(it) }
        } catch (e: Exception) {
            report(
                "marking processing job completed failed; program stays COMPLETED, replay the callback. " +
                    "executed_file_id:$executedFileId",
                e,
            )
        }
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

    private fun report(
        message: String,
        e: Exception,
        dryRun: Boolean = false,
    ) {
        logger.error(message, e)
        if (!dryRun) slackClient.notify("$message, e:${e.message}\nstackTrace:\n```\n${e.stackTraceToString()}\n```")
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
            Files.delete(file.toPath())
        }
    }
}
