package me.pinfort.tsvideos.processing.api

import me.pinfort.tsvideos.processing.external.slack.SlackClient
import me.pinfort.tsvideos.processing.pipeline.FileProcessingPipeline
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.nio.file.Path

// The constructor is internal: the pipeline and the Slack client are processing internals.
@Component
class PathProcessingRunner internal constructor(
    private val fileProcessingPipeline: FileProcessingPipeline,
    private val slackClient: SlackClient,
    private val logger: Logger,
    private val cancellation: ProcessingCancellation = ProcessingCancellation(),
) {
    data class Summary(
        val processed: Int = 0,
        val skipped: Int = 0,
        val quarantined: Int = 0,
        val failed: Int = 0,
    ) {
        operator fun plus(other: Summary) =
            Summary(
                processed + other.processed,
                skipped + other.skipped,
                quarantined + other.quarantined,
                failed + other.failed,
            )
    }

    fun processPath(
        path: Path,
        dryRun: Boolean = false,
        onDropCheckProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onEmergencyCheckProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onCompressProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onUploadProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Summary {
        cancellation.check()
        val target = path.toFile()
        if (!target.exists()) {
            logger.error("path not found, path=$path")
            slackClient.notify("path not exist path:$path")
            return Summary(failed = 1)
        }

        logger.info("Processing, path=$path")

        val files =
            try {
                expandRecordingInputs(path).map(Path::toFile)
            } catch (e: Exception) {
                logger.error("listing path failed, path=$path", e)
                slackClient.notify("path could not be listed path:$path reason:${e.message}")
                return Summary(failed = 1)
            }
        var summary = Summary()
        files.forEach { file ->
            cancellation.check()
            try {
                val result =
                    fileProcessingPipeline.processFile(
                        file,
                        dryRun,
                        onDropCheckProgress,
                        onEmergencyCheckProgress,
                        onCompressProgress,
                        onUploadProgress,
                    )
                summary +=
                    when (result) {
                        FileProcessingPipeline.Result.PROCESSED, FileProcessingPipeline.Result.DRY_RUN -> Summary(processed = 1)
                        FileProcessingPipeline.Result.QUARANTINED -> Summary(quarantined = 1)
                        FileProcessingPipeline.Result.SKIPPED_ALREADY_REGISTERED -> Summary(skipped = 1)
                    }
            } catch (e: Exception) {
                if (ProcessingCancellation.isInterrupted(e)) throw e
                summary += Summary(failed = 1)
                logger.error("processing file failed, file=$file", e)
                val message = "processing file failed. file:$file reason:${e.message}\nstackTrace:\n```\n${e.stackTraceToString()}\n```"
                slackClient.notify(message)
            }
        }
        return summary
    }
}
