package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.processor.infrastructure.external.slack.SlackClient
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Path

@Component
class PathProcessingRunner(
    private val fileProcessingPipeline: FileProcessingPipeline,
    private val slackClient: SlackClient,
    private val logger: Logger,
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
        onCompressProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onUploadProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Summary {
        val target = path.toFile()
        if (!target.exists()) {
            logger.error("path not found, path=$path")
            slackClient.notify("path not exist path:$path")
            return Summary(failed = 1)
        }

        logger.info("Processing, path=$path")

        var summary = Summary()
        resolveFiles(target).forEach { file ->
            try {
                summary +=
                    when (fileProcessingPipeline.processFile(file, dryRun, onDropCheckProgress, onCompressProgress, onUploadProgress)) {
                        FileProcessingPipeline.Result.PROCESSED, FileProcessingPipeline.Result.DRY_RUN -> Summary(processed = 1)
                        FileProcessingPipeline.Result.QUARANTINED -> Summary(quarantined = 1)
                        FileProcessingPipeline.Result.SKIPPED_ALREADY_REGISTERED -> Summary(skipped = 1)
                    }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                summary += Summary(failed = 1)
                logger.error("processing file failed, file=$file", e)
                val message = "processing file failed. file:$file reason:${e.message}\nstackTrace:\n```\n${e.stackTraceToString()}\n```"
                slackClient.notify(message)
            }
        }
        return summary
    }

    private fun resolveFiles(target: File): List<File> =
        if (target.isDirectory) {
            target
                .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".m2ts") }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            listOf(target)
        }
}
