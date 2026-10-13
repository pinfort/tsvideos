package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.component.ProcessingCancellation
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
    private val cancellation: ProcessingCancellation = ProcessingCancellation(),
    private val concurrency: ProcessingConcurrency = ProcessingConcurrency(),
) {
    data class Progress(
        val path: String,
        val stage: String,
        val value: Long,
        val total: Long,
    )

    data class Completion(
        val path: String,
        val outcome: String,
    )

    data class BatchResult(
        val completed: Int,
        val failed: Int,
    )

    fun processPaths(
        paths: List<Path>,
        dryRun: Boolean = false,
        progress: (Progress) -> Unit = {},
        complete: (Completion) -> Unit = {},
    ): BatchResult =
        cancellation.runOwned {
            val completed =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val failed =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val executor = concurrency.executor(cancellation)
            try {
                paths.forEach { path ->
                    cancellation.check()
                    val target = path.toFile()
                    if (!target.exists()) {
                        failed.incrementAndGet()
                        slackClient.notify("Recording unavailable: $path")
                        complete(Completion(path.toString(), "FAILED"))
                    } else {
                        resolveFiles(target).forEach { file ->
                            cancellation.check()
                            executor.execute {
                                try {
                                    cancellation.runOwned {
                                        val canonical = file.canonicalPath

                                        fun listener(stage: String): (Long, Long) -> Unit =
                                            { value, total ->
                                                cancellation.check()
                                                progress(Progress(canonical, stage, value, total))
                                            }
                                        try {
                                            val result =
                                                fileProcessingPipeline.processFile(
                                                    file,
                                                    dryRun,
                                                    listener("INSPECT"),
                                                    listener("COMPRESS"),
                                                    listener("UPLOAD"),
                                                )
                                            completed.incrementAndGet()
                                            complete(Completion(canonical, result.name))
                                        } catch (failure: Exception) {
                                            if (ProcessingCancellation.isInterrupted(failure)) throw failure
                                            failed.incrementAndGet()
                                            // Avoid expanding tool exception messages or stack traces into Slack.
                                            logger.error("Recording failed: {} ({})", canonical, failure.javaClass.simpleName)
                                            slackClient.notify("Recording failed: $canonical (${failure.javaClass.simpleName})")
                                            complete(Completion(canonical, "FAILED"))
                                        }
                                    }
                                } catch (failure: Exception) {
                                    if (ProcessingCancellation.isInterrupted(failure)) {
                                        cancellation.requestStop()
                                    } else {
                                        failed.incrementAndGet()
                                        logger.error("Worker failed", failure)
                                    }
                                }
                            }
                        }
                    }
                }
                executor.shutdown()
                while (!executor.awaitTermination(100, java.util.concurrent.TimeUnit.MILLISECONDS)) cancellation.check()
                cancellation.check()
                BatchResult(completed.get(), failed.get())
            } finally {
                executor.shutdownNow()
            }
        }

    /** Compatibility entry point; CLI submits all paths through one bounded batch. */
    fun processPath(
        path: Path,
        dryRun: Boolean = false,
        onDropCheckProgress: (Long, Long) -> Unit = { _, _ -> },
        onCompressProgress: (Long, Long) -> Unit = { _, _ -> },
        onUploadProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        processPaths(listOf(path), dryRun, { event ->
            when (event.stage) {
                "INSPECT" -> onDropCheckProgress(event.value, event.total)
                "COMPRESS" -> onCompressProgress(event.value, event.total)
                else -> onUploadProgress(event.value, event.total)
            }
        })
    }

    private fun resolveFiles(target: File): List<File> =
        if (target.isDirectory) {
            target
                .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".m2ts") }
                ?.sortedBy { it.name } ?: emptyList()
        } else {
            listOf(target)
        }
}
