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
    fun processPath(
        path: Path,
        dryRun: Boolean = false,
        onDropCheckProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onCompressProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onUploadProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        val target = path.toFile()
        if (!target.exists()) {
            logger.error("path not found, path=$path")
            slackClient.notify("path not exist path:$path")
            return
        }

        logger.info("Processing, path=$path")

        resolveFiles(target).forEach { file ->
            try {
                fileProcessingPipeline.processFile(file, dryRun, onDropCheckProgress, onCompressProgress, onUploadProgress)
            } catch (e: Exception) {
                logger.error("processing file failed, file=$file", e)
                val message = "processing file failed. file:$file reason:${e.message}\nstackTrace:\n```\n${e.stackTraceToString()}\n```"
                slackClient.notify(message)
            }
        }
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
