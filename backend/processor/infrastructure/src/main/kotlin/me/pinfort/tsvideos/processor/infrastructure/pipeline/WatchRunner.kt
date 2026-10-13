package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.command.ProcessingJobCommand
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import org.springframework.stereotype.Component
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Component
class WatchRunner(
    private val recordings: PathProcessingRunner,
    private val jobs: ProcessingJobCommand,
    private val concurrency: ProcessingConcurrency,
    private val cancellation: ProcessingCancellation,
) {
    data class Event(
        val code: String,
        val path: String,
    )

    private data class Directory(
        val path: Path,
        var failures: Int = 0,
        var retryAt: Long = Long.MIN_VALUE,
        var fileWarningAt: Long = Long.MIN_VALUE,
    )

    private data class OpenDirectory(
        val directory: Directory,
        val root: Path,
        val stream: DirectoryStream<Path>,
        val entries: Iterator<Path>,
    )

    fun run(
        options: WatchOptions,
        dryRun: Boolean = false,
        event: (Event) -> Unit = {},
        progress: (PathProcessingRunner.Progress) -> Unit = {},
        clock: () -> Long = System::nanoTime,
        pause: (Long) -> Unit = Thread::sleep,
    ): Int =
        cancellation.runOwned {
            val tracker = WatchReadiness(options.capacity)
            val executor = concurrency.executor(cancellation)
            val directories =
                options.directories
                    .map { it.toAbsolutePath().normalize() }
                    .distinct()
                    .map { Directory(it) }
            var scan = 0L
            try {
                try {
                    while (true) {
                        cancellation.check()
                        scan++
                        val open = mutableListOf<OpenDirectory>()

                        fun unavailable(directory: Directory) {
                            directory.failures++
                            val backoff = minOf(60L, 1L shl minOf(directory.failures, 6))
                            directory.retryAt = clock() + TimeUnit.SECONDS.toNanos(backoff)
                            event(Event("WATCH_DIRECTORY_UNAVAILABLE", directory.path.toString()))
                        }
                        try {
                            // Rotate the starting directory; advance one entry per directory each round.
                            directories.indices.forEach { offset ->
                                val directory = directories[((scan - 1 + offset) % directories.size).toInt()]
                                if (clock() >= directory.retryAt) {
                                    try {
                                        check(!Files.isSymbolicLink(directory.path))
                                        val root = directory.path.toRealPath()
                                        val stream = Files.newDirectoryStream(root)
                                        open.add(OpenDirectory(directory, root, stream, stream.iterator()))
                                        if (directory.failures > 0) event(Event("WATCH_DIRECTORY_RECOVERED", directory.path.toString()))
                                        directory.failures = 0
                                    } catch (failure: Exception) {
                                        if (ProcessingCancellation.isInterrupted(failure)) throw failure
                                        unavailable(directory)
                                    }
                                }
                            }
                            val remaining = open.toMutableList()
                            while (remaining.isNotEmpty()) {
                                remaining.toList().forEach { source ->
                                    cancellation.check()
                                    val candidate =
                                        try {
                                            if (!source.entries.hasNext()) {
                                                remaining.remove(source)
                                                null
                                            } else {
                                                source.entries.next()
                                            }
                                        } catch (failure: Exception) {
                                            if (ProcessingCancellation.isInterrupted(failure)) throw failure
                                            unavailable(source.directory)
                                            remaining.remove(source)
                                            null
                                        } ?: return@forEach
                                    try {
                                        if (options.extensions.none {
                                                candidate.fileName.toString().endsWith(
                                                    it,
                                                    ignoreCase = true,
                                                )
                                            }
                                        ) {
                                            return@forEach
                                        }
                                        val identity = snapshot(candidate, source.root) ?: return@forEach
                                        val marker =
                                            options.completion == "marker" && markerReady(candidate, identity, options.markerSuffix)
                                        if (!tracker.ready(identity, clock(), scan, options.stableNanos, marker)) return@forEach
                                        if (snapshot(candidate, source.root) != identity ||
                                            (options.completion == "marker" && !markerReady(candidate, identity, options.markerSuffix))
                                        ) {
                                            return@forEach
                                        }
                                        tracker.admit(identity)
                                        executor.execute {
                                            try {
                                                cancellation.runOwned {
                                                    if (snapshot(candidate, source.root) != identity ||
                                                        (
                                                            options.completion == "marker" &&
                                                                !markerReady(candidate, identity, options.markerSuffix)
                                                        )
                                                    ) {
                                                        tracker.changedBeforeProcessing(identity)
                                                        event(Event("WATCH_INPUT_CHANGED", identity.path))
                                                    } else if (dryRun) {
                                                        event(Event("WATCH_READY_DRY_RUN", identity.path))
                                                    } else {
                                                        // Existing states are authoritative; watch never retries a previous job.
                                                        val previous = jobs.find(identity) ?: jobs.findByPath(identity.path)
                                                        if (previous != null) {
                                                            event(Event("WATCH_EXISTING_JOB", identity.path))
                                                        } else {
                                                            cancellation.check()
                                                            val result =
                                                                recordings.processRecording(
                                                                    candidate.toFile(),
                                                                    false,
                                                                    progress,
                                                                    identity,
                                                                )
                                                            event(Event(result.outcome, result.path))
                                                        }
                                                    }
                                                }
                                            } catch (failure: Exception) {
                                                if (failure is WatchedInputChanged) {
                                                    tracker.changedBeforeProcessing(identity)
                                                    event(Event("WATCH_INPUT_CHANGED", identity.path))
                                                } else if (ProcessingCancellation.isInterrupted(failure)) {
                                                    cancellation.requestStop()
                                                } else {
                                                    event(Event("WATCH_OPERATION_FAILED", identity.path))
                                                }
                                                // Retain admitted version even if DB/claim failed: no automatic retry loop.
                                            }
                                        }
                                    } catch (failure: Exception) {
                                        if (failure is WatchCapacityExceeded || ProcessingCancellation.isInterrupted(failure)) throw failure
                                        val now = clock()
                                        if (source.directory.fileWarningAt == Long.MIN_VALUE ||
                                            now - source.directory.fileWarningAt >= TimeUnit.SECONDS.toNanos(60)
                                        ) {
                                            source.directory.fileWarningAt = now
                                            event(Event("WATCH_FILE_UNAVAILABLE", source.directory.path.toString()))
                                        }
                                    }
                                }
                            }
                            tracker.finishScan(scan)
                        } finally {
                            open.forEach {
                                try {
                                    it.stream.close()
                                } catch (failure: Exception) {
                                    if (ProcessingCancellation.isInterrupted(failure)) throw failure
                                    event(Event("WATCH_DIRECTORY_UNAVAILABLE", it.directory.path.toString()))
                                }
                            }
                        }
                        cancellation.check()
                        pause(options.pollSeconds * 1000)
                    }
                } catch (_: WatchCapacityExceeded) {
                    event(Event("WATCH_CAPACITY_EXCEEDED", ""))
                }
                // Capacity exhaustion stops admission but drains previously accepted work normally.
                executor.shutdown()
                while (!executor.awaitTermination(100, TimeUnit.MILLISECONDS)) cancellation.check()
                cancellation.check()
                4
            } finally {
                executor.shutdownNow()
            }
        }

    private fun snapshot(
        path: Path,
        root: Path,
    ): ProcessingIdentity? {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
        val canonical = path.toRealPath()
        if (canonical.parent != root) return null
        return ProcessingIdentity.from(canonical.toFile())
    }

    private fun markerReady(
        path: Path,
        identity: ProcessingIdentity,
        suffix: String,
    ): Boolean {
        val marker = path.resolveSibling(path.fileName.toString() + suffix)
        return Files.isRegularFile(marker, NOFOLLOW_LINKS) &&
            Files.getLastModifiedTime(marker, NOFOLLOW_LINKS).toMillis() >= identity.modifiedMillis
    }
}
