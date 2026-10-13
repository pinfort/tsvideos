package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ProcessingHistoryCommand
import me.pinfort.tsvideos.core.command.ProcessingJobCommand
import me.pinfort.tsvideos.core.command.ProcessingRecoveryCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.command.VerifiedUploadCommand
import me.pinfort.tsvideos.core.component.CompressComponent
import me.pinfort.tsvideos.core.component.MainSplittedFileFinderComponent
import me.pinfort.tsvideos.core.component.NasDestinationResolver
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.core.domain.CheckpointEvidence
import me.pinfort.tsvideos.core.domain.ExecutedFile
import me.pinfort.tsvideos.core.domain.FileName
import me.pinfort.tsvideos.core.domain.ProcessingArtifact
import me.pinfort.tsvideos.core.domain.ProcessingAttemptKind
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.domain.ProcessingOutcome
import me.pinfort.tsvideos.core.domain.ProcessingStage
import me.pinfort.tsvideos.core.domain.ResumeBlocked
import me.pinfort.tsvideos.core.domain.SplittedFile
import me.pinfort.tsvideos.core.exception.TsVideosException
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.core.external.tool.AmatsukazeAddTaskClient
import me.pinfort.tsvideos.core.external.tool.DurationProbeClient
import me.pinfort.tsvideos.core.external.tool.TsSplitterClient
import me.pinfort.tsvideos.processor.infrastructure.external.tsselect.TsSelectClient
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File
import kotlin.math.ceil

/**
 * DropCheck(tsselect) -> TsSplitter -> CompressAndSave -> AmatsukazeAddTask の4段パイプライン。
 * 登録・分割段階の失敗は逆順にロールバックする。アップロード開始後は復旧用の記録とローカルデータを保持する。
 */
@Component
class FileProcessingPipeline(
    private val executedFileCommand: ExecutedFileCommand,
    private val splittedFileCommand: SplittedFileCommand,
    private val programCommand: ProgramCommand,
    private val tsSelectClient: TsSelectClient,
    private val tsSplitterClient: TsSplitterClient,
    private val amatsukazeAddTaskClient: AmatsukazeAddTaskClient,
    private val durationProbeClient: DurationProbeClient,
    private val mainSplittedFileFinderComponent: MainSplittedFileFinderComponent,
    private val compressComponent: CompressComponent,
    private val nasDestinationResolver: NasDestinationResolver,
    private val processorToolConfigurationProperties: ProcessorToolConfigurationProperties,
    private val logger: Logger,
    private val processingJobCommand: ProcessingJobCommand,
    private val verifiedUploadCommand: VerifiedUploadCommand,
    private val history: ProcessingHistoryCommand,
    private val recovery: ProcessingRecoveryCommand,
    private val cancellation: ProcessingCancellation = ProcessingCancellation(),
) {
    enum class Result {
        PROCESSED,
        DRY_RUN,
        SKIPPED_ALREADY_REGISTERED,
    }

    private data class RecordingInspection(
        val fileName: FileName,
        val drops: Int,
        val duration: Double,
    )

    fun processFile(
        file: File,
        dryRun: Boolean = false,
        onDropCheckProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onCompressProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onUploadProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result {
        cancellation.check()
        if (!file.isFile) throw TsVideosException("file not found or not a regular file, file=$file")
        val identity = ProcessingIdentity.from(file)
        if (dryRun) {
            val inspection = inspect(file, onDropCheckProgress) ?: return Result.SKIPPED_ALREADY_REGISTERED
            logger.info(
                "Dry run: checked file=$file, drops=${inspection.drops}, duration=${inspection.duration}; " +
                    "would register, split, compress, upload and submit encoding",
            )
            return Result.DRY_RUN
        }
        val existing = processingJobCommand.find(identity)
        if (existing != null && existing.state != "RETRYABLE") {
            check(existing.state in setOf("SUBMITTED", "COMPLETED")) {
                "Job ${existing.id} is ${existing.state}; reconcile before retrying (encoder submission may have succeeded)"
            }
            return Result.SKIPPED_ALREADY_REGISTERED
        }
        processingJobCommand.findByPath(identity.path)?.let {
            check(it.identityKey == identity.key || it.state == "RETRYABLE") {
                "Changed recording version requires reconciliation/reset of job ${it.id} (${it.state})"
            }
        }
        executedFileCommand.findByFile(identity.path)?.let {
            error("Recording path already registered: changed or legacy input requires reset before processing")
        }
        val job =
            processingJobCommand.claim(identity)
                ?: error("Recording already claimed by another invocation")
        val token = recovery.acquire(job.id, false)
        return processClaimed(file, identity, job, token, false, onDropCheckProgress, onCompressProgress, onUploadProgress)
    }

    fun resume(jobId: Long): Result {
        val token = recovery.acquire(jobId, true)
        try {
            val plan = recovery.plan(jobId, token)
            if (plan.blocked || plan.mode != "PROCESS") throw ResumeBlocked("PREREQUISITES_CHANGED")
            val job = recovery.job(jobId)
            return processClaimed(
                File(job.inputPath),
                ProcessingIdentity.from(File(job.inputPath)),
                job,
                token,
                true,
                { _, _ -> },
                { _, _ -> },
                { _, _ -> },
            )
        } finally {
            ProcessingCancellation.recovery { recovery.release(jobId, token) }
        }
    }

    private fun processClaimed(
        file: File,
        identity: ProcessingIdentity,
        job: ProcessingJob,
        token: String,
        resume: Boolean,
        onDropCheckProgress: (Long, Long) -> Unit,
        onCompressProgress: (Long, Long) -> Unit,
        onUploadProgress: (Long, Long) -> Unit,
    ): Result {
        val attempt =
            try {
                history.begin(job, if (resume) ProcessingAttemptKind.RESUME else ProcessingAttemptKind.PROCESS)
            } catch (
                e: Exception,
            ) {
                ProcessingCancellation.recovery { recovery.release(job.id, token) }
                throw e
            }
        val checkpoints = if (resume) recovery.evidence(job.id) else emptyMap()

        fun save(
            stage: String,
            evidence: CheckpointEvidence,
        ) = recovery.save(job.id, attempt.id, token, stage, evidence)
        var uploadStarted = false
        try {
            val inspection =
                checkpoints["INSPECT"]?.let {
                    RecordingInspection(FileName.fromFileNameString(file.name), checkNotNull(it.drops), checkNotNull(it.duration))
                } ?: history.stage(
                    attempt.id,
                    ProcessingStage.INSPECT,
                    { ProcessingArtifact("INPUT", job.id, identity.path) },
                ) {
                    val inspected =
                        inspect(file, history.progress(attempt.id, onDropCheckProgress))
                            ?: error("Recording name already registered; reconcile or reset before processing")
                    check(ProcessingIdentity.from(file) == identity) { "Recording changed during inspection; retry when stable" }
                    save("INSPECT", CheckpointEvidence(drops = inspected.drops, duration = inspected.duration))
                    inspected
                }
            val runner = RollbackRunner()
            var registered: ExecutedFile? = null
            val executedFile =
                checkpoints["REGISTER"]?.referenceId?.let { checkNotNull(executedFileCommand.find(it)) }
                    ?: history.stage(attempt.id, ProcessingStage.REGISTER) {
                        runner
                            .stage({
                                registered?.let {
                                    rollbackRegistration(it)
                                    recovery.invalidate(job.id, token)
                                }
                            }) {
                                registerRecording(File(identity.path), inspection).also {
                                    registered = it
                                    processingJobCommand.link(job, it.id)
                                    save("REGISTER", CheckpointEvidence(referenceId = it.id))
                                }
                            }
                    }
            var splitInvoked = false
            val mainSplittedFile =
                checkpoints["SPLIT"]?.let { checkpoint ->
                    checkpoint.files.forEach(recovery::verify)
                    splittedFileCommand.selectByExecutedFileId(executedFile.id).single { it.id == checkpoint.referenceId }
                } ?: history.stage(attempt.id, ProcessingStage.SPLIT, { ProcessingArtifact("SPLITTED_FILE", it.id, it.file) }) {
                    runner
                        .stage({ if (splitInvoked) rollbackTsSplit(executedFile) }) {
                            tsSplit(executedFile) { splitInvoked = true }.also { main ->
                                save(
                                    "SPLIT",
                                    CheckpointEvidence(
                                        splittedFileCommand.selectByExecutedFileId(executedFile.id).map {
                                            recovery.capture(File(it.file), it.id)
                                        },
                                        main.id,
                                    ),
                                )
                            }
                        }
                }
            uploadStarted = true
            // Preserve registration, split and upload evidence after any external upload starts.
            compressAndSave(
                mainSplittedFile,
                attempt.id,
                job.id,
                token,
                checkpoints,
                history.progress(attempt.id, onCompressProgress),
                history.progress(attempt.id, onUploadProgress),
            )
            // Once submission starts rollback would destroy input possibly already consumed by the encoder.
            history.stage(attempt.id, ProcessingStage.SUBMIT_ENCODER) {
                cancellation.check()
                recovery.evidence(job.id)["SPLIT"]?.files?.forEach(recovery::verify)
                processingJobCommand.transition(job, "CLAIMED", "SUBMISSION_UNKNOWN")
                cancellation.check()
                amatsukazeAddTask(mainSplittedFile)
                cancellation.check()
                processingJobCommand.acknowledgeSubmission(job)
                save("SUBMIT_ENCODER", CheckpointEvidence(referenceId = mainSplittedFile.id))
            }
            history.finish(attempt.id, ProcessingOutcome.COMPLETED)
        } catch (e: Exception) {
            try {
                history.failed(attempt.id, e)
            } catch (historyFailure: Exception) {
                e.addSuppressed(historyFailure)
            }
            if (!uploadStarted && e.suppressed.isEmpty() && executedFileCommand.findByFile(identity.path) == null) {
                processingJobCommand.transition(job, "CLAIMED", "RETRYABLE")
            }
            // Keep durable evidence even after an external effect succeeded but its DB update failed.
            logger.error("Processing job ${job.id} requires reconciliation", e)
            throw e
        } finally {
            ProcessingCancellation.recovery { recovery.release(job.id, token) }
        }
        return Result.PROCESSED
    }

    // Read-only checks shared by normal processing and dry-run, before any rollback is needed.
    private fun inspect(
        file: File,
        onProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit,
    ): RecordingInspection? {
        if (!file.isFile) {
            throw TsVideosException("file not found or not a regular file, file=$file")
        }
        programCommand.findByName(file.name)?.let {
            logger.info("Program already registered, skip processing, file=$file, program=$it")
            return null
        }
        val fileName = FileName.fromFileNameString(file.name)
        val drops = tsSelectClient.check(file, onProgress)
        val duration = durationProbeClient.probe(file)
        return RecordingInspection(fileName, drops, duration)
    }

    // Stage 1: register the inspected recording as executed_file + program.
    private fun registerRecording(
        file: File,
        inspection: RecordingInspection,
    ): ExecutedFile {
        val (fileName, drops, duration) = inspection
        val executedFile =
            executedFileCommand.insert(
                file = file.absolutePath,
                drops = drops,
                size = file.length(),
                recordedAt = fileName.recordedAt,
                channel = fileName.channel,
                title = fileName.title,
                channelName = fileName.channelName,
                duration = duration,
            )
        try {
            programCommand.insert(file.name, executedFile.id)
        } catch (e: Exception) {
            executedFileCommand.delete(executedFile)
            throw e
        }

        return executedFile
    }

    private fun rollbackRegistration(executedFile: ExecutedFile) {
        programCommand.deleteByExecutedFileId(executedFile.id)
        executedFileCommand.delete(executedFile)
    }

    // Stage 2: split into elementary streams, register splitted_file rows, pick the main file
    private fun tsSplit(
        executedFile: ExecutedFile,
        beforeSplit: () -> Unit,
    ): SplittedFile {
        val originalFile = File(executedFile.file)
        if (!originalFile.exists()) {
            throw TsVideosException("file not found, file=$originalFile")
        }

        val outDir = File(originalFile.parentFile, "tssplitter")
        if (!outDir.exists()) {
            outDir.mkdirs()
        }

        if (findSplitFiles(originalFile, outDir).isNotEmpty()) {
            throw TsVideosException("splitted file already exists, originalFile=$originalFile")
        }

        val timeoutSec = maxOf(ceil(executedFile.duration).toLong(), 600L)
        beforeSplit()
        val exitCode = tsSplitterClient.split(originalFile, outDir, timeoutSec)
        if (exitCode != 0) {
            throw TsVideosException("TsSplitter failed, exitCode=$exitCode, originalFile=$originalFile")
        }

        val foundFiles = findSplitFiles(originalFile, outDir)
        if (foundFiles.isEmpty()) {
            throw TsVideosException("no splitted file found, originalFile=$originalFile")
        }

        val insertedSplittedFiles =
            foundFiles.map { splitFile ->
                val duration = durationProbeClient.probe(splitFile)
                splittedFileCommand.insert(executedFile.id, splitFile.absolutePath, splitFile.length(), duration)
            }
        executedFileCommand.updateStatus(executedFile, ExecutedFile.Status.SPLITTED)

        return mainSplittedFileFinderComponent.find(executedFile, insertedSplittedFiles)
    }

    private fun rollbackTsSplit(executedFile: ExecutedFile) {
        val originalFile = File(executedFile.file)
        val outDir = File(originalFile.parentFile, "tssplitter")
        findSplitFiles(originalFile, outDir).forEach { it.delete() }
        splittedFileCommand.selectByExecutedFileId(executedFile.id).forEach { splittedFileCommand.delete(it) }
    }

    private fun findSplitFiles(
        originalFile: File,
        outDir: File,
    ): List<File> {
        val stem = originalFile.nameWithoutExtension
        return outDir
            .listFiles { candidate -> candidate.name.startsWith(stem) && candidate.name.endsWith(".m2ts") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    // Stage 3: gzip-compress the main split file and upload it to the original-store NAS
    private fun compressAndSave(
        splittedFile: SplittedFile,
        attemptId: Long,
        jobId: Long,
        token: String,
        checkpoints: Map<String, CheckpointEvidence>,
        onCompressProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
        onUploadProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ) {
        val splitFile = File(splittedFile.file)
        val compressedFile = File(splitFile.parentFile, "${splitFile.name}.gz")

        recovery.evidence(jobId)["SPLIT"]?.files?.forEach(recovery::verify)
        val retainedUpload = if (checkpoints.isNotEmpty()) verifiedUploadCommand.inspectRecovery(jobId, "original") else null
        if (retainedUpload?.state !in setOf("FINALIZED", "REGISTERED")) {
            val compressed = checkpoints["COMPRESS"]
            if (compressed != null) {
                compressed.files.forEach(recovery::verify)
            } else {
                history.stage(attemptId, ProcessingStage.COMPRESS) {
                    if (!compressComponent.compress(splitFile, compressedFile, false, onCompressProgress)) {
                        error("Compressed file already exists; reconcile before processing")
                    }
                    recovery.evidence(jobId)["SPLIT"]?.files?.forEach(recovery::verify)
                    recovery.save(jobId, attemptId, token, "COMPRESS", CheckpointEvidence(listOf(recovery.capture(compressedFile))))
                }
            }
        }

        val targetFile =
            nasDestinationResolver.resolve(
                splitFile.parentFile.parentFile.toPath(),
                compressedFile.name,
                SambaClient.NasType.ORIGINAL_STORE_NAS,
            )

        val job = checkNotNull(processingJobCommand.findByExecutedFile(splittedFile.executedFileId))
        val receipt =
            history.stage(
                attemptId,
                ProcessingStage.UPLOAD_ORIGINAL,
                { ProcessingArtifact("UPLOAD_MANIFEST", it.manifestId, targetFile) },
            ) {
                val uploaded =
                    verifiedUploadCommand.upload(
                        job.id,
                        "original",
                        compressedFile,
                        SambaClient.NasType.ORIGINAL_STORE_NAS,
                        targetFile,
                        onUploadProgress,
                    )
                verifiedUploadCommand.register(uploaded, splittedFile.id, "video/vnd.dlna.mpeg-tts", "gzip")
                splittedFileCommand.updateStatus(splittedFile, SplittedFile.Status.COMPRESS_SAVED)
                recovery.save(jobId, attemptId, token, "UPLOAD_ORIGINAL", CheckpointEvidence(referenceId = uploaded.manifestId))
                uploaded
            }
        history.stage(attemptId, ProcessingStage.FINALIZE) { verifiedUploadCommand.deleteLocal(receipt) }
    }

    // Stage 4: submit the main split file to the running Amatsukaze server
    private fun amatsukazeAddTask(splittedFile: SplittedFile) {
        val executedFile =
            executedFileCommand.find(splittedFile.executedFileId)
                ?: throw TsVideosException("executed file not found, id=${splittedFile.executedFileId}")

        val splitFile = File(splittedFile.file)
        val outDir = File(splitFile.parentFile, "encoded")
        if (!outDir.exists()) {
            outDir.mkdirs()
        }

        check(amatsukazeAddTaskClient.addTask(splitFile, outDir, decideProfile(executedFile)) == 0) {
            "Encoder submission returned failure; outcome must be reconciled"
        }
    }

    private fun rollbackAmatsukazeAddTask(splittedFile: SplittedFile) {
        logger.info("Nothing to rollback for AmatsukazeAddTask, splittedFile=$splittedFile")
    }

    private val atxDivTitleRegex = Regex("#[0-9]{1,3}-#[0-9]{1,3}")

    private fun decideProfile(executedFile: ExecutedFile): String {
        val amatsukaze = processorToolConfigurationProperties.amatsukaze
        val isAtxDiv =
            executedFile.channelName == "ＡＴ－Ｘ" &&
                executedFile.duration > 10800 &&
                atxDivTitleRegex.containsMatchIn(executedFile.title)
        return if (isAtxDiv) amatsukaze.atxDivProfile else amatsukaze.defaultProfile
    }
}
