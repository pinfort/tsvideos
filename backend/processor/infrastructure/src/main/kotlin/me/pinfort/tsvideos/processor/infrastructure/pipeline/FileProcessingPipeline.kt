package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileTagCommand
import me.pinfort.tsvideos.core.command.ProcessingHistoryCommand
import me.pinfort.tsvideos.core.command.ProcessingJobCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.RecordingQualityCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.command.VerifiedUploadCommand
import me.pinfort.tsvideos.core.component.CompressComponent
import me.pinfort.tsvideos.core.component.MainSplittedFileFinderComponent
import me.pinfort.tsvideos.core.component.NasDestinationResolver
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.component.RecordingQualityInspector
import me.pinfort.tsvideos.core.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.core.domain.ExecutedFile
import me.pinfort.tsvideos.core.domain.ExecutedFileCheck
import me.pinfort.tsvideos.core.domain.FileName
import me.pinfort.tsvideos.core.domain.ProcessingArtifact
import me.pinfort.tsvideos.core.domain.ProcessingArtifactType
import me.pinfort.tsvideos.core.domain.ProcessingAttempt
import me.pinfort.tsvideos.core.domain.ProcessingAttemptKind
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.domain.ProcessingOutcome
import me.pinfort.tsvideos.core.domain.ProcessingStage
import me.pinfort.tsvideos.core.domain.QualityAction
import me.pinfort.tsvideos.core.domain.SplittedFile
import me.pinfort.tsvideos.core.exception.TsVideosException
import me.pinfort.tsvideos.core.external.tool.AmatsukazeAddTaskClient
import me.pinfort.tsvideos.core.external.tool.DurationProbeClient
import me.pinfort.tsvideos.core.external.tool.TsSplitterClient
import me.pinfort.tsvideos.processor.infrastructure.external.ts.EmergencyBroadcastDetector
import me.pinfort.tsvideos.processor.infrastructure.external.tsselect.TsSelectClient
import me.pinfort.tsvideos.storage.api.NasStore
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File
import kotlin.math.ceil

/**
 * DropCheck(tsselect + 緊急警報放送/文字スーパー検出) -> TsSplitter -> CompressAndSave -> AmatsukazeAddTask の4段パイプライン。
 * 登録・分割・圧縮段階の失敗は逆順にロールバックする。アップロード開始後は復旧用の記録とローカルデータを保持する。
 */
@Component
class FileProcessingPipeline(
    private val executedFileCommand: ExecutedFileCommand,
    private val executedFileTagCommand: ExecutedFileTagCommand,
    private val splittedFileCommand: SplittedFileCommand,
    private val programCommand: ProgramCommand,
    private val tsSelectClient: TsSelectClient,
    private val emergencyBroadcastDetector: EmergencyBroadcastDetector,
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
    private val quality: RecordingQualityInspector,
    private val qualityReports: RecordingQualityCommand,
    private val cancellation: ProcessingCancellation = ProcessingCancellation(),
) {
    enum class Result {
        PROCESSED,
        QUARANTINED,
        DRY_RUN,
        SKIPPED_ALREADY_REGISTERED,
    }

    private data class RecordingInspection(
        val fileName: FileName,
        val drops: Int,
        val duration: Double,
        // null when emergency broadcast detection failed: the recording is left "not checked"
        val emergencyTags: Set<String>?,
    )

    fun processFile(
        file: File,
        dryRun: Boolean = false,
        onDropCheckProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onEmergencyCheckProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onCompressProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onUploadProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result {
        cancellation.check()
        if (!file.isFile) throw TsVideosException("file not found or not a regular file, file=$file")
        val identity = ProcessingIdentity.from(file)
        if (dryRun) {
            val inspection = inspect(file, onDropCheckProgress, onEmergencyCheckProgress) ?: return Result.SKIPPED_ALREADY_REGISTERED
            if (quality.enabled()) {
                val decision = quality.inspect(file, inspection.drops, inspection.duration)
                logger.info("Dry run quality: $decision")
                if (decision.action == QualityAction.QUARANTINE) {
                    logger.info("Dry run: would quarantine in place at ${identity.path}; no registration or processing")
                    return Result.QUARANTINED
                }
            }
            logger.info(
                "Dry run: checked file=$file, drops=${inspection.drops}, duration=${inspection.duration}, " +
                    "tags=${inspection.emergencyTags}; " +
                    "would register, split, compress, upload and submit encoding",
            )
            return Result.DRY_RUN
        }
        val existing = processingJobCommand.find(identity)
        if (existing?.state == "QUARANTINED") {
            logger.warn("Job ${existing.id} remains quarantined in place at ${identity.path}; explicit quality release required")
            return Result.QUARANTINED
        }
        if (existing != null && existing.state != "RETRYABLE") {
            check(existing.state in setOf("SUBMITTED", "COMPLETED")) { blockedJobMessage(existing) }
            return Result.SKIPPED_ALREADY_REGISTERED
        }
        processingJobCommand.findByPath(identity.path)?.let {
            check(it.identityKey == identity.key || it.state == "RETRYABLE") {
                "Changed recording version requires reconciliation of job ${it.id} (${it.state}); " +
                    "see docs/processing-idempotency.md, then run `tvpcli reset ${identity.path}`"
            }
        }
        executedFileCommand.findByFile(identity.path)?.let {
            error("Recording path already registered without a matching job; run `tvpcli reset ${identity.path}` before processing")
        }
        // Checked before claiming so a name collision leaves no job behind; inspect() re-checks it after the claim.
        programCommand.findByName(file.name)?.let { error(nameAlreadyRegistered(file)) }
        val job =
            processingJobCommand.claim(identity)
                ?: error("Recording already claimed by another invocation")
        val runner = RollbackRunner()
        var started: ProcessingAttempt? = null
        var uploadStarted = false
        var submitted = false
        try {
            // Inside the try so that a failure to start history still releases the claim as RETRYABLE.
            val attempt = history.begin(job, ProcessingAttemptKind.PROCESS).also { started = it }
            val inspection =
                history.stage(
                    attempt.id,
                    ProcessingStage.INSPECT,
                    { ProcessingArtifact(ProcessingArtifactType.INPUT, job.id, identity.path) },
                ) {
                    val inspected =
                        inspect(
                            file,
                            history.progress(attempt.id, onDropCheckProgress),
                            history.progress(attempt.id, onEmergencyCheckProgress),
                        ) ?: error(nameAlreadyRegistered(file))
                    check(ProcessingIdentity.from(file) == identity) { "Recording changed during inspection; retry when stable" }
                    inspected
                }
            if (quality.enabled()) {
                val decision =
                    history.stage(attempt.id, ProcessingStage.QUALITY_CHECK) {
                        quality.inspect(file, inspection.drops, inspection.duration).also {
                            check(ProcessingIdentity.from(file) == identity) { "Recording changed during quality inspection" }
                        }
                    }
                // Outside the stage: a quarantine finishes the attempt in the same transaction as the report and the
                // QUARANTINED transition, so a failure here leaves the attempt ACTIVE for the catch below to fail.
                // Deliberately fatal for WARN/CONTINUE too: record() also re-verifies ownership, and nothing has reached
                // the NAS yet, so a failed write only costs a RETRYABLE rerun rather than processing without evidence.
                qualityReports.record(job, attempt.id, decision)
                logger.info("Quality decision job=${job.id}: $decision")
                if (decision.action == QualityAction.QUARANTINE) return Result.QUARANTINED
            }
            var registered: ExecutedFile? = null
            val executedFile =
                runner.stage({ registered?.let { rollbackRegistration(it) } }) {
                    registerRecording(File(identity.path), inspection).also {
                        registered = it
                        // Inside the stage so that a failed link rolls the registration back.
                        processingJobCommand.link(job, it.id)
                    }
                }
            var splitInvoked = false
            // A history write failing around SPLIT/COMPRESS rolls back work that succeeded. That is wasteful but
            // safe: nothing has reached the NAS yet, so the job is released as RETRYABLE.
            val mainSplittedFile =
                history.stage(
                    attempt.id,
                    ProcessingStage.SPLIT,
                    { ProcessingArtifact(ProcessingArtifactType.SPLITTED_FILE, it.id, it.file) },
                ) {
                    runner.stage({ if (splitInvoked) rollbackTsSplit(executedFile) }) {
                        tsSplit(executedFile) { splitInvoked = true }
                    }
                }
            var createdArchive: File? = null
            val archive =
                history.stage(attempt.id, ProcessingStage.COMPRESS) {
                    runner.stage({
                        // A retry would upload a leftover partial archive as-is, so remove the one this attempt created.
                        createdArchive?.let { check(it.delete() || !it.exists()) { "Cannot delete partial archive $it" } }
                    }) {
                        compressArchive(mainSplittedFile, history.progress(attempt.id, onCompressProgress)) { createdArchive = it }
                    }
                }
            uploadStarted = true
            // Preserve registration, split and upload evidence after any external upload starts.
            saveArchive(mainSplittedFile, archive, attempt.id, history.progress(attempt.id, onUploadProgress))
            // Once submission starts rollback would destroy input possibly already consumed by the encoder.
            history.stage(attempt.id, ProcessingStage.SUBMIT_ENCODER) {
                cancellation.check()
                processingJobCommand.transition(job, "CLAIMED", "SUBMISSION_UNKNOWN")
                cancellation.check()
                amatsukazeAddTask(mainSplittedFile)
                cancellation.check()
                processingJobCommand.acknowledgeSubmission(job)
                submitted = true
            }
            history.finish(attempt.id, ProcessingOutcome.COMPLETED)
        } catch (e: Exception) {
            if (submitted) {
                // The job is SUBMITTED and owned by the encoder now; only the history write failed.
                logger.error("Recording history of submitted job ${job.id} failed; processing itself succeeded", e)
                return Result.PROCESSED
            }
            started?.let {
                try {
                    history.failed(it.id, e)
                } catch (historyFailure: Exception) {
                    e.addSuppressed(historyFailure)
                }
            }
            // Retry is only safe when nothing reached the NAS and every rollback finished.
            if (!uploadStarted && runner.rolledBackCleanly && markRetryable(job, identity, e)) {
                logger.error("Processing job ${job.id} failed and was rolled back; it can be retried", e)
            } else {
                // Keep durable evidence even after an external effect succeeded but its DB update failed.
                logger.error("Processing job ${job.id} requires reconciliation", e)
            }
            throw e
        }
        return Result.PROCESSED
    }

    private fun nameAlreadyRegistered(file: File) =
        "Recording name ${file.name} already registered; run `tvpcli reset` on that recording before processing"

    private fun blockedJobMessage(job: ProcessingJob): String {
        val reason =
            when (job.state) {
                "CLAIMED" ->
                    "another invocation is processing it, or an earlier one stopped after its upload started " +
                        "or without finishing rollback"
                "SUBMISSION_UNKNOWN" -> "encoder submission may have succeeded; check the encoder queue before reconciling"
                else -> "finish its reset (`tvpcli reset ${job.inputPath}`) before processing"
            }
        return "Job ${job.id} is ${job.state} (last updated ${job.updatedAt} UTC): $reason. See docs/processing-idempotency.md"
    }

    // Must not replace the processing failure being reported: a failure here leaves the job CLAIMED.
    private fun markRetryable(
        job: ProcessingJob,
        identity: ProcessingIdentity,
        failure: Exception,
    ): Boolean {
        try {
            if (executedFileCommand.findByFile(identity.path) != null) return false
            processingJobCommand.transition(job, "CLAIMED", "RETRYABLE")
            return true
        } catch (e: Exception) {
            failure.addSuppressed(e)
            return false
        }
    }

    // Read-only checks (drop-frame count, emergency broadcast detection, duration) shared by
    // normal processing and dry-run, before any rollback is needed.
    private fun inspect(
        file: File,
        onProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit,
        onEmergencyCheckProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit,
    ): RecordingInspection? {
        if (!file.isFile) {
            throw TsVideosException("file not found or not a regular file, file=$file")
        }
        // Not redundant with the pre-claim check in processFile: dry-run relies on it, and it catches a
        // registration that raced in after the claim.
        programCommand.findByName(file.name)?.let {
            logger.info("Program already registered, skip processing, file=$file, program=$it")
            return null
        }
        val fileName = FileName.fromFileNameString(file.name)
        val drops = tsSelectClient.check(file, onProgress)
        val emergencyTags = detectEmergencyBroadcast(file, onEmergencyCheckProgress)
        val duration = durationProbeClient.probe(file)
        return RecordingInspection(fileName, drops, duration, emergencyTags)
    }

    // Detection is supplementary, so a failure must not stop processing: log it and return null so
    // the check is not recorded and the recording shows as "not checked".
    private fun detectEmergencyBroadcast(
        file: File,
        onProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit,
    ): Set<String>? {
        val emergency =
            try {
                emergencyBroadcastDetector.detect(file, onProgress)
            } catch (e: Exception) {
                logger.warn("Emergency broadcast detection failed, leave it unchecked, file=$file", e)
                return null
            }
        if (emergency.ewsDetected || emergency.superimposeDetected) {
            logger.warn("Emergency broadcast detected, file=$file, result=$emergency")
        }
        return emergency.tags()
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
        inspection.emergencyTags?.let { recordEmergencyCheck(executedFile, it) }

        return executedFile
    }

    // Like detection itself, saving its result is supplementary: if it fails (e.g. the tag tables have
    // not been created yet), keep the registration and leave the recording "not checked".
    private fun recordEmergencyCheck(
        executedFile: ExecutedFile,
        tags: Set<String>,
    ) {
        try {
            executedFileTagCommand.recordCheck(executedFile.id, ExecutedFileCheck.EMERGENCY_BROADCAST, tags)
        } catch (e: Exception) {
            logger.warn("Recording emergency broadcast tags failed, leave it unchecked, executedFile=$executedFile", e)
        }
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

    // Stage 3a: gzip-compress the main split file next to it
    private fun compressArchive(
        splittedFile: SplittedFile,
        onCompressProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
        onArchiveCreating: (File) -> Unit,
    ): File {
        val splitFile = File(splittedFile.file)
        val compressedFile = File(splitFile.parentFile, "${splitFile.name}.gz")

        if (!compressedFile.exists()) onArchiveCreating(compressedFile)
        if (!compressComponent.compress(splitFile, compressedFile, false, onCompressProgress)) {
            error("Compressed file already exists; reconcile before processing")
        }
        return compressedFile
    }

    // Stage 3b: verified upload of the archive to the original-store NAS; the local archive is removed only once registered
    private fun saveArchive(
        splittedFile: SplittedFile,
        compressedFile: File,
        attemptId: Long,
        onUploadProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ) {
        val targetFile =
            nasDestinationResolver.resolve(
                File(splittedFile.file).parentFile.parentFile.toPath(),
                compressedFile.name,
                NasStore.ORIGINAL_STORE_NAS,
            )

        val job = checkNotNull(processingJobCommand.findByExecutedFile(splittedFile.executedFileId))
        val receipt =
            history.stage(
                attemptId,
                ProcessingStage.UPLOAD_ORIGINAL,
                { ProcessingArtifact(ProcessingArtifactType.UPLOAD_MANIFEST, it.manifestId, targetFile) },
            ) {
                val uploaded =
                    verifiedUploadCommand.upload(
                        job.id,
                        "original",
                        compressedFile,
                        NasStore.ORIGINAL_STORE_NAS,
                        targetFile,
                        onUploadProgress,
                    )
                verifiedUploadCommand.register(uploaded, splittedFile.id, "video/vnd.dlna.mpeg-tts", "gzip")
                splittedFileCommand.updateStatus(splittedFile, SplittedFile.Status.COMPRESS_SAVED)
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
