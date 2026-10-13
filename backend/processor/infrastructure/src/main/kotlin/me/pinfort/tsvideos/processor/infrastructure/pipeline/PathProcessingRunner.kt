package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.component.ProcessingStageTiming
import me.pinfort.tsvideos.core.component.ProcessingStageTimings
import me.pinfort.tsvideos.processor.infrastructure.external.slack.SlackClient
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Component
class PathProcessingRunner(
    private val fileProcessingPipeline: FileProcessingPipeline,
    private val slackClient: SlackClient,
    private val logger: Logger,
    private val cancellation: ProcessingCancellation = ProcessingCancellation(),
    private val concurrency: ProcessingConcurrency = ProcessingConcurrency(),
    private val timings: ProcessingStageTimings = ProcessingStageTimings(),
    @Value("\${processor.shutdown.grace-seconds:30}") private val graceSeconds: Long = 30,
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

    private class Pending(
        val ordinal: Int,
        val path: String,
    ) {
        @Volatile var started = false
        val result = AtomicReference<RecordingReport?>()
    }

    init {
        require(graceSeconds in 1..300)
    }

    fun processReport(
        paths: List<Path>,
        dryRun: Boolean = false,
        notifications: BatchNotification = BatchNotification.PER_FILE,
        progress: (Progress) -> Unit = {},
        complete: (Completion) -> Unit = {},
    ): ProcessingBatchReport {
        val startedAt = Instant.now().toString()
        val entries = mutableListOf<Pending>()
        var interrupted = false
        var discovered = false
        val executor = concurrency.executor(cancellation)
        val notificationInterrupted = AtomicBoolean(false)

        fun notify(message: String) {
            if (dryRun || notifications == BatchNotification.NONE) return
            try {
                slackClient.notify(message)
            } catch (failure: Exception) {
                if (ProcessingCancellation.isInterrupted(failure)) Thread.currentThread().interrupt()
                logger.warn("Batch notification unavailable")
            } finally {
                if (Thread.currentThread().isInterrupted) {
                    notificationInterrupted.set(true)
                    cancellation.requestStop()
                }
            }
        }

        fun unavailable(
            path: Path,
            reason: String,
        ) {
            val pending = Pending(entries.size, path.toAbsolutePath().normalize().toString())
            entries.add(pending)
            pending.result.set(RecordingReport(pending.ordinal, pending.path, BatchOutcome.FAILED, reason, 0, emptyList()))
            if (notifications == BatchNotification.PER_FILE) notify("Recording failed: ${pending.path} ($reason)")
            complete(Completion(pending.path, "FAILED"))
        }
        try {
            cancellation.runOwned {
                try {
                    paths.forEach { path ->
                        cancellation.check()
                        val target = path.toFile()
                        val files =
                            try {
                                when {
                                    !target.exists() -> {
                                        unavailable(
                                            path,
                                            if (java.nio.file.Files
                                                    .notExists(path)
                                            ) {
                                                "INPUT_MISSING"
                                            } else {
                                                "INPUT_INACCESSIBLE"
                                            },
                                        )
                                        emptyList()
                                    }
                                    target.isDirectory ->
                                        target
                                            .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".m2ts") }
                                            ?.sortedBy { it.name } ?: run {
                                            unavailable(path, "DIRECTORY_INACCESSIBLE")
                                            emptyList()
                                        }
                                    !target.isFile -> {
                                        unavailable(path, "INPUT_NOT_REGULAR")
                                        emptyList()
                                    }
                                    else -> listOf(target)
                                }
                            } catch (_: SecurityException) {
                                unavailable(path, "INPUT_INACCESSIBLE")
                                emptyList()
                            }
                        files.forEach { file ->
                            cancellation.check()
                            val canonical =
                                try {
                                    file.canonicalPath
                                } catch (_: Exception) {
                                    unavailable(file.toPath(), "INPUT_INACCESSIBLE")
                                    return@forEach
                                }
                            val pending = Pending(entries.size, canonical)
                            entries.add(pending)
                            executor.execute {
                                val started = System.nanoTime()
                                val stages = mutableListOf<ProcessingStageTiming>()
                                var outcome = BatchOutcome.INTERRUPTED
                                var reason = "INTERRUPTED"
                                try {
                                    cancellation.runOwned {
                                        pending.started = true

                                        fun listener(stage: String): (Long, Long) -> Unit =
                                            { value, total ->
                                                cancellation.check()
                                                progress(Progress(canonical, stage, value, total))
                                            }
                                        val result =
                                            timings.observe(stages) {
                                                fileProcessingPipeline.processFile(
                                                    file,
                                                    dryRun,
                                                    listener("INSPECT"),
                                                    listener("COMPRESS"),
                                                    listener("UPLOAD"),
                                                )
                                            }
                                        outcome =
                                            when (result) {
                                                FileProcessingPipeline.Result.PROCESSED -> BatchOutcome.SUBMITTED
                                                FileProcessingPipeline.Result.DRY_RUN -> BatchOutcome.DRY_RUN
                                                FileProcessingPipeline.Result.SKIPPED_ALREADY_REGISTERED ->
                                                    BatchOutcome.SKIPPED_ALREADY_REGISTERED
                                            }
                                        reason = outcome.name
                                    }
                                } catch (failure: Exception) {
                                    if (ProcessingCancellation.isInterrupted(failure)) {
                                        outcome = if (pending.started) BatchOutcome.INTERRUPTED else BatchOutcome.NOT_STARTED
                                        reason = "INTERRUPTED"
                                        cancellation.requestStop()
                                    } else {
                                        outcome = BatchOutcome.FAILED
                                        reason = if (failure is IllegalArgumentException) "INVALID_INPUT" else "PROCESSING_FAILED"
                                        logger.error("Recording failed: {} ({})", canonical, failure.javaClass.simpleName)
                                        if (notifications == BatchNotification.PER_FILE) notify("Recording failed: $canonical ($reason)")
                                    }
                                } finally {
                                    pending.result.set(
                                        RecordingReport(
                                            pending.ordinal,
                                            canonical,
                                            outcome,
                                            reason,
                                            TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - started).coerceAtLeast(0)),
                                            stages.toList(),
                                        ),
                                    )
                                    complete(Completion(canonical, outcome.name))
                                }
                            }
                        }
                    }
                    discovered = true
                    executor.shutdown()
                    while (!executor.awaitTermination(100, TimeUnit.MILLISECONDS)) cancellation.check()
                    cancellation.check()
                } catch (failure: Exception) {
                    if (!ProcessingCancellation.isInterrupted(failure)) throw failure
                    interrupted = true
                    cancellation.requestStop()
                } finally {
                    executor.shutdownNow()
                    // Briefly clear interruption only for bounded worker settlement; restore it afterward.
                    val hadInterrupt = Thread.interrupted()
                    try {
                        if (!executor.awaitTermination(graceSeconds, TimeUnit.SECONDS)) interrupted = true
                    } catch (_: InterruptedException) {
                        interrupted = true
                    } finally {
                        if (hadInterrupt || interrupted) Thread.currentThread().interrupt()
                    }
                }
            }
        } catch (failure: Exception) {
            if (!ProcessingCancellation.isInterrupted(failure)) throw failure
            interrupted = true
        } finally {
            executor.shutdownNow()
        }
        val records =
            entries.map { entry ->
                entry.result.get() ?: RecordingReport(
                    entry.ordinal,
                    entry.path,
                    if (entry.started) BatchOutcome.INTERRUPTED else BatchOutcome.NOT_STARTED,
                    "INTERRUPTED",
                    0,
                    emptyList(),
                )
            }
        val report = ProcessingBatchReport(startedAt, Instant.now().toString(), dryRun, interrupted, discovered, records)
        if (notifications == BatchNotification.SUMMARY && !interrupted) {
            notify(
                "Processing batch: submitted=${report.submitted}, skipped=${report.skipped}, " +
                    "failed=${report.failed}, exit=${report.exitCode}",
            )
        }
        return if (notificationInterrupted.get()) report.copy(interrupted = true, endedAt = Instant.now().toString()) else report
    }

    /** Compatibility API: interrupted callers retain the original exception contract. */
    fun processPaths(
        paths: List<Path>,
        dryRun: Boolean = false,
        progress: (Progress) -> Unit = {},
        complete: (Completion) -> Unit = {},
    ): BatchResult {
        val report = processReport(paths, dryRun, BatchNotification.PER_FILE, progress, complete)
        if (report.interrupted) throw InterruptedException("Processing interrupted")
        return BatchResult(report.submitted + report.skipped + report.planned, report.failed)
    }

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
}
