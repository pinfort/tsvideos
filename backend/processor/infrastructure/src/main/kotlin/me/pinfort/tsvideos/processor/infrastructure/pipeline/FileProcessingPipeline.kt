package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.command.CreatedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileTagCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.component.CompressComponent
import me.pinfort.tsvideos.core.component.MainSplittedFileFinderComponent
import me.pinfort.tsvideos.core.component.NasDestinationResolver
import me.pinfort.tsvideos.core.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.core.domain.ExecutedFile
import me.pinfort.tsvideos.core.domain.ExecutedFileCheck
import me.pinfort.tsvideos.core.domain.FileName
import me.pinfort.tsvideos.core.domain.SplittedFile
import me.pinfort.tsvideos.core.exception.TsVideosException
import me.pinfort.tsvideos.core.external.samba.NasComponent
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.core.external.tool.AmatsukazeAddTaskClient
import me.pinfort.tsvideos.core.external.tool.DurationProbeClient
import me.pinfort.tsvideos.core.external.tool.TsSplitterClient
import me.pinfort.tsvideos.processor.infrastructure.external.ts.EmergencyBroadcastDetector
import me.pinfort.tsvideos.processor.infrastructure.external.tsselect.TsSelectClient
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File
import kotlin.math.ceil

/**
 * DropCheck(tsselect + 緊急警報放送/文字スーパー検出) -> TsSplitter -> CompressAndSave -> AmatsukazeAddTask の4段パイプライン。
 * 各段は失敗すると自身とそれ以前の段を逆順にロールバックしてから例外を再送出する。
 */
@Component
class FileProcessingPipeline(
    private val executedFileCommand: ExecutedFileCommand,
    private val executedFileTagCommand: ExecutedFileTagCommand,
    private val splittedFileCommand: SplittedFileCommand,
    private val createdFileCommand: CreatedFileCommand,
    private val programCommand: ProgramCommand,
    private val tsSelectClient: TsSelectClient,
    private val emergencyBroadcastDetector: EmergencyBroadcastDetector,
    private val tsSplitterClient: TsSplitterClient,
    private val amatsukazeAddTaskClient: AmatsukazeAddTaskClient,
    private val durationProbeClient: DurationProbeClient,
    private val mainSplittedFileFinderComponent: MainSplittedFileFinderComponent,
    private val compressComponent: CompressComponent,
    private val nasComponent: NasComponent,
    private val nasDestinationResolver: NasDestinationResolver,
    private val processorToolConfigurationProperties: ProcessorToolConfigurationProperties,
    private val logger: Logger,
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
        val tags: Set<String>,
    )

    fun processFile(
        file: File,
        dryRun: Boolean = false,
        onDropCheckProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onEmergencyCheckProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onCompressProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onUploadProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result {
        val inspection = inspect(file, onDropCheckProgress, onEmergencyCheckProgress) ?: return Result.SKIPPED_ALREADY_REGISTERED
        if (dryRun) {
            logger.info(
                "Dry run: checked file=$file, drops=${inspection.drops}, duration=${inspection.duration}, " +
                    "tags=${inspection.tags}; " +
                    "would register, split, compress, upload and submit encoding",
            )
            return Result.DRY_RUN
        }
        val runner = RollbackRunner()
        val executedFile = runner.stage({ rollbackRegistration(file) }) { registerRecording(file, inspection) }

        val mainSplittedFile = runner.stage({ rollbackTsSplit(executedFile) }) { tsSplit(executedFile) }

        runner.stage({ rollbackCompressAndSave(mainSplittedFile) }) {
            compressAndSave(mainSplittedFile, onCompressProgress, onUploadProgress)
        }

        runner.stage({ rollbackAmatsukazeAddTask(mainSplittedFile) }) {
            amatsukazeAddTask(mainSplittedFile)
        }

        return Result.PROCESSED
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
        programCommand.findByName(file.name)?.let {
            logger.info("Program already registered, skip processing, file=$file, program=$it")
            return null
        }
        val fileName = FileName.fromFileNameString(file.name)
        val drops = tsSelectClient.check(file, onProgress)
        val emergency = emergencyBroadcastDetector.detect(file, onEmergencyCheckProgress)
        if (emergency.ewsDetected || emergency.superimposeDetected) {
            logger.warn("Emergency broadcast detected, file=$file, result=$emergency")
        }
        val duration = durationProbeClient.probe(file)
        return RecordingInspection(fileName, drops, duration, emergency.tags())
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
        programCommand.insert(file.name, executedFile.id)
        executedFileTagCommand.recordCheck(executedFile.id, ExecutedFileCheck.EMERGENCY_BROADCAST, inspection.tags)

        return executedFile
    }

    private fun rollbackRegistration(file: File) {
        val executedFile = executedFileCommand.findByFile(file.absolutePath)
        if (executedFile == null) {
            logger.warn("No executed file to rollback, file=$file")
            return
        }
        programCommand.deleteByExecutedFileId(executedFile.id)
        executedFileCommand.delete(executedFile)
    }

    // Stage 2: split into elementary streams, register splitted_file rows, pick the main file
    private fun tsSplit(executedFile: ExecutedFile): SplittedFile {
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
        onCompressProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
        onUploadProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ) {
        val splitFile = File(splittedFile.file)
        val compressedFile = File(splitFile.parentFile, "${splitFile.name}.gz")

        if (!compressComponent.compress(splitFile, compressedFile, false, onCompressProgress)) {
            logger.error("Compress skipped, compressed file already exists, splitFile=$splitFile")
            return
        }

        val targetFile =
            nasDestinationResolver.resolve(
                splitFile.parentFile.parentFile.toPath(),
                compressedFile.name,
                SambaClient.NasType.ORIGINAL_STORE_NAS,
            )

        nasComponent.uploadResource(compressedFile, targetFile, SambaClient.NasType.ORIGINAL_STORE_NAS, onUploadProgress)
        createdFileCommand.insert(
            splittedFile.id,
            targetFile,
            compressedFile.length(),
            "video/vnd.dlna.mpeg-tts",
            "gzip",
        )
        splittedFileCommand.updateStatus(splittedFile, SplittedFile.Status.COMPRESS_SAVED)
        compressedFile.delete()
    }

    private fun rollbackCompressAndSave(splittedFile: SplittedFile) {
        createdFileCommand
            .selectBySplittedFileId(splittedFile.id)
            .filter { it.encoding == "gzip" }
            .forEach { createdFileCommand.delete(it) }
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

        amatsukazeAddTaskClient.addTask(splitFile, outDir, decideProfile(executedFile))
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
