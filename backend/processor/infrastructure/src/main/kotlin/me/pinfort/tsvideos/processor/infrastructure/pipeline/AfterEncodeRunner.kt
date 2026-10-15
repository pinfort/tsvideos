package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.command.CreatedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ProcessingJobCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.component.MimeTypeComponent
import me.pinfort.tsvideos.core.component.NasDestinationResolver
import me.pinfort.tsvideos.core.component.ValidateCompletedComponent
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.SplittedFile
import me.pinfort.tsvideos.core.external.samba.NasComponent
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.processor.infrastructure.external.slack.SlackClient
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Path

/**
 * Amatsukaze のエンコード実行後バッチから呼び出される処理。
 * エンコード済みファイルを created_file として登録し、NAS へアップロードしたうえで
 * 元ファイルを削除し、番組を COMPLETED にする。
 *
 * FileProcessingPipeline と異なりロールバックは行わない。
 * NAS へのアップロードとローカルファイルの削除が済んだ後で失敗を巻き戻すことはできないため、
 * 失敗時はログと Slack 通知を行い番組を ERROR にするだけに留める。
 */
@Component
class AfterEncodeRunner(
    private val splittedFileCommand: SplittedFileCommand,
    private val createdFileCommand: CreatedFileCommand,
    private val executedFileCommand: ExecutedFileCommand,
    private val programCommand: ProgramCommand,
    private val nasComponent: NasComponent,
    private val nasDestinationResolver: NasDestinationResolver,
    private val mimeTypeComponent: MimeTypeComponent,
    private val validateCompletedComponent: ValidateCompletedComponent,
    private val slackClient: SlackClient,
    private val logger: Logger,
    private val processingJobCommand: ProcessingJobCommand,
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

    private data class RegisteredFile(
        val localFile: File,
        val createdFile: CreatedFile,
    )

    fun run(
        input: Input,
        dryRun: Boolean = false,
        onUploadProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        if (!input.success) {
            notifyError(input)
            return
        }

        val splittedFile = findOriginalSplittedFile(input.inPath)
        if (splittedFile == null) {
            // executedFileId が取れないと後続の完了処理ができないため、何も登録せずに中断する
            logger.error("original splitted file not found, inPath=${input.inPath}")
            slackClient.notify("original splitted file not found. in_path:${input.inPath}")
            return
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
                report("claiming encode callback failed. in_path:${input.inPath}", e)
                return
            }
        // claim 前のスナップショット。この呼び出し内での完了は finishProcess の中で完結し、catch には到達しない。
        val alreadyCompleted = job?.state == "COMPLETED"
        var completed = false
        try {
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
            val registeredFiles = registerFiles(splittedFile, input.files, dryRun)
            moveFiles(registeredFiles, dryRun, onUploadProgress)
            completed = finishProcess(splittedFile.executedFileId, input.inPath, dryRun)
        } catch (e: Exception) {
            // アップロード済みのファイルや削除済みのローカルファイルは戻せないため、
            // 通知して番組を ERROR にするだけに留める。無人のバッチから呼ばれるので、
            // 例外をそのまま投げると誰も気づけない。
            if (alreadyCompleted) {
                logger.error("Rejected callback for completed job ${job.id}; existing outcome preserved", e)
                return
            }
            notifyFailure(
                splittedFile.executedFileId,
                "registering or uploading encoded files failed. in_path:${input.inPath}",
                e,
                dryRun,
            )
            return
        } finally {
            if (job != null && token != null) releaseCallback(job, token)
        }
        if (completed) logger.info("uploading file completed, targets=${input.files}")
    }

    /** 解放の失敗で処理結果の例外を上書きしないよう、通知だけ行う。残った claim は手動での reconcile が必要。 */
    private fun releaseCallback(
        job: ProcessingJob,
        token: String,
    ) {
        try {
            processingJobCommand.releaseCallback(job, token)
        } catch (e: Exception) {
            report("releasing callback claim of job ${job.id} failed; reconcile the stale claim $token", e)
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

    private fun registerFiles(
        splittedFile: SplittedFile,
        files: List<Path>,
        dryRun: Boolean,
    ): List<RegisteredFile> =
        files.map { path ->
            val localFile = path.toFile()
            val mimeType = mimeTypeComponent.guess(localFile.name)
            val previous = createdFileCommand.selectBySplittedFileId(splittedFile.id).firstOrNull { it.file == nasTargetFile(path) }
            if (previous == null && !dryRun) {
                check(localFile.isFile) { "Unregistered callback output missing" }
                check(!nasComponent.resourceExists(nasTargetFile(path))) { "Unowned NAS target exists; reconciliation required" }
            }
            val createdFile =
                previous
                    ?: createdFileCommand.insert(
                        splittedFile.id,
                        nasTargetFile(path),
                        localFile.length(),
                        mimeType.mime,
                        mimeType.encoding,
                        CreatedFile.Status.ENCODE_SUCCESS,
                        dryRun,
                    )
            check(dryRun || createdFile.status == CreatedFile.Status.FILE_MOVED || localFile.isFile) {
                "Pending callback output missing; reconciliation required"
            }
            check(!localFile.exists() || localFile.length() == createdFile.size) { "Callback output size changed" }
            logger.info("created file registered, file=$localFile, splittedFileId=${splittedFile.id}")
            RegisteredFile(localFile, createdFile)
        }

    private fun nasTargetFile(file: Path): String =
        nasDestinationResolver.resolve(
            file.parent.parent.parent,
            file.fileName.toString(),
            SambaClient.NasType.VIDEO_STORE_NAS,
        )

    private fun moveFiles(
        registeredFiles: List<RegisteredFile>,
        dryRun: Boolean,
        onUploadProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ) {
        registeredFiles.forEach { (localFile, createdFile) ->
            if (createdFile.status == CreatedFile.Status.FILE_MOVED) {
                deleteLocalFile(localFile, dryRun)
                return@forEach
            }
            check(localFile.isFile || dryRun) { "Pending output is missing; reconciliation required" }
            if (!dryRun) {
                nasComponent.uploadResource(
                    localFile,
                    createdFile.file,
                    SambaClient.NasType.VIDEO_STORE_NAS,
                    onUploadProgress,
                )
            }
            if (!dryRun) {
                check(nasComponent.matchesSize(createdFile.file, createdFile.size, SambaClient.NasType.VIDEO_STORE_NAS)) {
                    "Upload has uncertain outcome or size mismatch; local output preserved"
                }
            }
            createdFileCommand.updateStatus(createdFile, CreatedFile.Status.FILE_MOVED, dryRun)
            logger.info("file uploaded, file=${localFile.name}, target=${createdFile.file}")
            deleteLocalFile(localFile, dryRun)
        }
    }

    /** 番組を COMPLETED にできたときだけ true を返す。失敗はここで通知済み。 */
    private fun finishProcess(
        executedFileId: Long,
        inPath: Path,
        dryRun: Boolean,
    ): Boolean {
        val program = programCommand.findByExecutedFileId(executedFileId)
        if (program == null) {
            // この時点でNASへのアップロードは済んでいるため、他の失敗と同様に通知する
            logger.error("program not found, executedFileId=$executedFileId")
            slackClient.notify("program not found. executed_file_id:$executedFileId")
            return false
        }

        try {
            if (validateCompletedComponent.validate(program.id)) {
                logger.info("program valid, status will be completed, programId=${program.id}")
                deleteLocalFile(inPath.toFile(), dryRun)
                removeExecutedFile(executedFileId, dryRun)
                programCommand.updateStatusByExecutedFileId(executedFileId, Program.Status.COMPLETED, dryRun)
                if (!dryRun) completeJob(executedFileId)
                return true
            }
            logger.error("program invalid, programId=${program.id}")
            slackClient.notify("program invalid. programId:${program.id}")
            programCommand.updateStatusByExecutedFileId(executedFileId, Program.Status.ERROR, dryRun)
        } catch (e: Exception) {
            notifyFailure(executedFileId, "program invalid. programId:${program.id}", e, dryRun)
        }
        return false
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
        report(message, e)
        programCommand.updateStatusByExecutedFileId(executedFileId, Program.Status.ERROR, dryRun)
    }

    private fun report(
        message: String,
        e: Exception,
    ) {
        logger.error(message, e)
        slackClient.notify("$message, e:${e.message}\nstackTrace:\n```\n${e.stackTraceToString()}\n```")
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
        if (!file.exists()) return
        logger.info("removing file, file=$file")
        if (!dryRun) {
            file.delete()
        }
    }
}
