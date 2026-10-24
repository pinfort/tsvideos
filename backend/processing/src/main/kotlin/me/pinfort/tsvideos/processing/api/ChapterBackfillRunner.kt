package me.pinfort.tsvideos.processing.api

import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.processing.api.tool.ChapterProbeClient
import org.slf4j.Logger
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Probes the chapters of mp4 `created_file` rows registered before chapters were recorded (or whose probe failed).
 *
 * The NAS is reached through smbj, which ffprobe cannot read, so the operator passes the local or UNC path of the
 * VIDEO_STORE_NAS share root (e.g. `\\nas\videos`) and each share-relative `created_file.file` is joined under it. A
 * file is probed only when the joined path stays under that root, exists and has the recorded size, so a wrong root
 * is reported instead of probing unrelated files. Each recording is its own short transaction, so an interrupted run
 * keeps what it recorded and a re-run continues with the rest. See backend/docs/video-chapters.md.
 */
@Component
class ChapterBackfillRunner(
    private val createdFileCatalog: CreatedFileCatalog,
    private val chapterProbeClient: ChapterProbeClient,
    transactionManager: PlatformTransactionManager,
    private val logger: Logger,
    private val cancellation: ProcessingCancellation = ProcessingCancellation(),
) {
    private val transaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    enum class Outcome(
        val failure: Boolean,
    ) {
        /** Chapters recorded (in a dry run: found and would be recorded). */
        CHAPTERS(false),

        /** Probed and the file has no chapters; recorded as probed with none (in a dry run: would be). */
        NO_CHAPTERS(false),

        /** Another writer recorded the chapters first; nothing changed. */
        ALREADY_PROBED(false),

        /** ffprobe failed; the row stays not probed. */
        PROBE_FAILED(true),

        /** No regular file at the joined path (or it could not be read). */
        MISSING(true),

        /** The file exists but its size differs from `created_file.size`; most likely a wrong root. */
        SIZE_MISMATCH(true),

        /** The stored path cannot be joined safely under the root (absolute, traversal, drive or stream syntax). */
        INVALID_PATH(true),
    }

    data class FileResult(
        val createdFileId: Long,
        val file: String,
        val localPath: Path?,
        val outcome: Outcome,
        /** Number of chapters found; null when the file was not probed successfully. */
        val chapters: Int? = null,
    )

    data class Summary(
        val counts: Map<Outcome, Int> = emptyMap(),
    ) {
        operator fun get(outcome: Outcome): Int = counts[outcome] ?: 0

        operator fun plus(outcome: Outcome): Summary = Summary(counts + (outcome to get(outcome) + 1))

        val failed: Int get() = Outcome.entries.filter { it.failure }.sumOf { get(it) }
    }

    /**
     * Processes every mp4 row whose chapters are not probed, in id order, reporting each file to [onResult] as it
     * goes. [dryRun] probes but writes nothing. Interruption propagates; what was recorded before it stays committed.
     */
    fun run(
        videoStoreRoot: Path,
        dryRun: Boolean,
        onResult: (FileResult) -> Unit = {},
    ): Summary {
        require(Files.isDirectory(videoStoreRoot)) { "Video store root is not a directory: $videoStoreRoot" }
        val root = videoStoreRoot.toAbsolutePath().normalize()
        var summary = Summary()
        var cursor = 0L
        while (true) {
            cancellation.check()
            val batch = createdFileCatalog.videosWithoutChapters(cursor, BATCH_SIZE)
            if (batch.isEmpty()) break
            batch.forEach { file ->
                cancellation.check()
                val result = backfill(root, file, dryRun)
                summary += result.outcome
                onResult(result)
            }
            cursor = batch.last().id
        }
        logger.info("Chapter backfill finished, dryRun=$dryRun, summary=${summary.counts}")
        return summary
    }

    private fun backfill(
        root: Path,
        file: CreatedFile,
        dryRun: Boolean,
    ): FileResult {
        val local = resolveUnder(root, file.file)
        if (local == null) {
            logger.warn("Skipping chapters of created file ${file.id}: path cannot be joined under the root, file=${file.file}")
            return FileResult(file.id, file.file, null, Outcome.INVALID_PATH)
        }
        val size = sizeOf(local) ?: return FileResult(file.id, file.file, local, Outcome.MISSING)
        if (size != file.size) {
            logger.warn("Skipping chapters of created file ${file.id}: $local has $size bytes, recorded ${file.size}")
            return FileResult(file.id, file.file, local, Outcome.SIZE_MISMATCH)
        }
        if (!staysUnder(root, local)) {
            logger.warn("Skipping chapters of created file ${file.id}: $local resolves outside the root")
            return FileResult(file.id, file.file, local, Outcome.INVALID_PATH)
        }
        val chapters =
            chapterProbeClient.probe(local.toFile()) ?: return FileResult(file.id, file.file, local, Outcome.PROBE_FAILED)
        // A stop requested while ffprobe ran must not be followed by a write.
        cancellation.check()
        val found = if (chapters.isEmpty()) Outcome.NO_CHAPTERS else Outcome.CHAPTERS
        if (dryRun) return FileResult(file.id, file.file, local, found, chapters.size)
        val recorded = transaction.execute { createdFileCatalog.recordChaptersIfMissing(file.id, chapters) } == true
        return FileResult(file.id, file.file, local, if (recorded) found else Outcome.ALREADY_PROBED, chapters.size)
    }

    /** The size of the regular file at [local]; null when there is none or it cannot be read. */
    private fun sizeOf(local: Path): Long? =
        try {
            if (Files.isRegularFile(local)) Files.size(local) else null
        } catch (e: IOException) {
            if (ProcessingCancellation.isInterrupted(e)) throw e
            logger.warn("Cannot read the size of $local", e)
            null
        }

    // A link inside the share must not lead the probe outside it; checked once the file is known to exist.
    private fun staysUnder(
        root: Path,
        local: Path,
    ): Boolean =
        try {
            local.toRealPath().startsWith(root.toRealPath())
        } catch (e: IOException) {
            if (ProcessingCancellation.isInterrupted(e)) throw e
            logger.warn("Cannot resolve the real path of $local", e)
            false
        }

    companion object {
        private const val BATCH_SIZE = 100

        /**
         * Joins the share-relative [relative] path (`/`-separated; `\` is accepted too, as SMB treats both as separators)
         * under [root], or null when it is not a plain relative path: absolute, empty or `.`/`..` segments, `:` (drive
         * letters, schemes, NTFS streams) or control characters. The result is also checked to stay under [root].
         */
        fun resolveUnder(
            root: Path,
            relative: String,
        ): Path? {
            if (relative.isBlank() || relative.any { it == ':' || it.isISOControl() }) return null
            val segments = relative.split('/', '\\')
            // Windows strips trailing dots, so a dots-only segment such as "..." is rejected along with "." and "..".
            if (segments.any { it.isBlank() || it.all { c -> c == '.' } }) return null
            val base = root.toAbsolutePath().normalize()
            val joined =
                try {
                    segments.fold(base) { path, segment -> path.resolve(segment) }.normalize()
                } catch (_: InvalidPathException) {
                    return null
                }
            return joined.takeIf { it != base && it.startsWith(base) }
        }
    }
}
