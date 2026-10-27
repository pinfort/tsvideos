package me.pinfort.tsvideos.catalog.api

import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.VideoChapter
import me.pinfort.tsvideos.catalog.domain.VideoChapters
import me.pinfort.tsvideos.catalog.domain.VideoResolution
import me.pinfort.tsvideos.catalog.persistence.CreatedFileChapterMapper
import me.pinfort.tsvideos.catalog.persistence.CreatedFileMapper
import me.pinfort.tsvideos.catalog.persistence.GeneratedKeyHolder
import me.pinfort.tsvideos.storage.api.NasStore
import org.slf4j.Logger
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Catalog read, registration and metadata-update operations on `created_file` rows. No NAS access and no
 * HTTP types: deleting and moving a file together with its NAS copy stay coordinated operations of
 * [CreatedFileCommand].
 *
 * Reads join the caller's transaction when there is one. Writes are `MANDATORY`: they never open or commit a
 * transaction of their own, so the caller decides what commits (or rolls back) together with them.
 */
@Component
class CreatedFileCatalog internal constructor(
    private val createdFileMapper: CreatedFileMapper,
    private val chapterMapper: CreatedFileChapterMapper,
    private val logger: Logger,
) {
    fun find(id: Long): CreatedFile? = createdFileMapper.find(id)?.toDomain()

    /** Created files of one recording by id; a row whose `splitted_file` is gone is not found. */
    fun filesOfRecording(executedFileId: Long): List<CreatedFile> =
        createdFileMapper.selectByExecutedFileId(executedFileId).sortedBy { it.id }.map { it.toDomain() }

    /** Every created file, grouped by recording (`executed_file_id`) and ordered by id; orphaned rows are skipped. */
    fun filesByRecording(): Map<Long, List<CreatedFile>> =
        createdFileMapper.selectAllWithExecutedFileId().groupBy({ it.executedFileId }, { it.toDomain() })

    /** `FILE_MOVED` files of the given recordings, grouped by recording and ordered by id. */
    fun movedFilesByRecording(executedFileIds: Collection<Long>): Map<Long, List<CreatedFile>> =
        executedFileIds
            .distinct()
            // Chunked so a huge set stays within placeholder limits.
            .chunked(ID_CHUNK_SIZE)
            .flatMap { ids -> createdFileMapper.selectMovedByExecutedFileIds(ids) }
            .groupBy({ it.executedFileId }, { it.toDomain() })
            .mapValues { (_, files) -> files.sortedBy { it.id } }

    /**
     * The video [id] may be played back: the row exists and its mime is `video/mp4`; anything else is treated as
     * missing. Playback reads VIDEO_STORE_NAS only, never the original store.
     */
    fun findStreamableVideo(id: Long): StreamableVideo? {
        val createdFile = find(id) ?: return null
        if (!createdFile.isMp4) return null
        return StreamableVideo(createdFile.id, NasStore.VIDEO_STORE_NAS, createdFile.file)
    }

    /**
     * Inserts a `FILE_MOVED` row for [file] on the split [splittedFileId] and returns it. Refused with
     * [IllegalStateException] (nothing kept) when the split does not exist, so a registration that lost a race with a
     * program delete never leaves an orphan row behind (`created_file` has no foreign key to `splitted_file`).
     *
     * Lock order (backend/docs/module-boundaries.md §7.3):
     * 1. the guards of [file] and of the split, in ascending key order like every guard claim
     *    ([LedgerLocks.lockCreatedFilePaths]). A bulk apply or move holding either guard makes the registration wait
     *    here, before it has written or locked anything else, until that change commits.
     * 2. the insert. It holds no ledger lock while it waits, so a guarded cleanup (whose full `created_file` scan stops
     *    every insert and which then locks every split) cannot deadlock with it.
     * 3. a share lock on the split, read as committed: absent once a delete has committed, and held so that the split
     *    cannot be deleted before this registration commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun register(
        splittedFileId: Long,
        file: String,
        size: Long,
        mime: String?,
        encoding: String?,
        resolution: VideoResolution? = null,
    ): CreatedFile {
        val status = CreatedFile.Status.FILE_MOVED
        val keyHolder = GeneratedKeyHolder()
        val pathKey = CreatedFilePathKey.hash(file)
        CreatedFilePathKey
            .claimOrder(listOf(pathKey, CreatedFilePathKey.splitGuard(splittedFileId)))
            .forEach { createdFileMapper.lockPathGuard(it) }
        createdFileMapper.insert(
            splittedFileId,
            file,
            pathKey,
            size,
            mime,
            encoding,
            status.name,
            keyHolder,
            resolution?.width,
            resolution?.height,
        )
        if (createdFileMapper.lockSplitShared(splittedFileId) == null) {
            // Removed here as well as by the caller's rollback, so no orphan row can commit even if the failure is swallowed.
            createdFileMapper.delete(keyHolder.id)
            throw IllegalStateException("Split $splittedFileId does not exist; created file $file was not registered")
        }
        val createdFile =
            CreatedFile(
                id = keyHolder.id,
                splittedFileId = splittedFileId,
                file = file,
                size = size,
                mime = mime,
                encoding = encoding,
                status = status,
                width = resolution?.width,
                height = resolution?.height,
            )
        logger.info("Insert created file, id=${createdFile.id}, createdFile=$createdFile")
        return createdFile
    }

    /** Sets the mime only while none is stored; returns whether this call set it. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun fillMimeIfMissing(
        id: Long,
        mime: String,
    ): Boolean = createdFileMapper.updateMimeIfMissing(id, mime) > 0

    /** Sets the resolution only while none is stored; returns whether this call set it. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun fillResolutionIfMissing(
        id: Long,
        resolution: VideoResolution,
    ): Boolean = createdFileMapper.updateResolutionIfMissing(id, resolution.width, resolution.height) > 0

    /**
     * Stores the path key ([CreatedFilePathKey]) of up to [limit] rows without one, in id order after [afterId]: rows
     * registered before migration 076 or written by SQL outside catalog. Returns the last id examined while more rows may
     * remain, or null when none is left after [afterId]. A row whose file changed meanwhile, or that another transaction
     * holds locked (a move or bulk apply in progress), is left for a later call: this never waits for a row lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun fillMissingPathKeys(
        afterId: Long,
        limit: Int,
    ): Long? {
        require(limit > 0) { "limit must be positive: $limit" }
        val rows = createdFileMapper.selectWithoutPathKey(afterId, limit)
        if (rows.isEmpty()) return null
        val unlocked = createdFileMapper.lockUnlockedRows(rows.map { it.id }).toSet()
        rows
            .filter { it.id in unlocked }
            .forEach { createdFileMapper.fillPathKey(it.id, it.file, CreatedFilePathKey.hash(it.file)) }
        return if (rows.size < limit) null else rows.last().id
    }

    /**
     * Whether the chapters of [createdFileId] were probed, and the chapters in index order; null when the row does not
     * exist. The flag is read first: it commits together with the chapters, so a probed flag is never paired with a
     * partial list.
     */
    fun chaptersOf(createdFileId: Long): VideoChapters? {
        val probed = chapterMapper.chaptersProbed(createdFileId) ?: return null
        if (!probed) return VideoChapters.NOT_PROBED
        return VideoChapters(true, chapterMapper.select(createdFileId).map { it.toDomain() })
    }

    /** The chapters of a video that [findStreamableVideo] would play; null for anything that is not streamable. */
    fun streamableVideoChapters(id: Long): VideoChapters? {
        if (findStreamableVideo(id) == null) return null
        return chaptersOf(id)
    }

    /**
     * Mp4 rows whose chapters were never probed, in id order after [afterId] (a keyset cursor, so a caller that records
     * as it goes neither skips nor revisits rows).
     */
    fun videosWithoutChapters(
        afterId: Long,
        limit: Int,
    ): List<CreatedFile> {
        require(limit > 0) { "limit must be positive: $limit" }
        return chapterMapper.selectUnprobedVideos(afterId, limit).map { it.toDomain() }
    }

    /**
     * Marks the chapters of [id] probed and stores [chapters] (possibly none), only while they are not probed yet;
     * returns whether this call recorded them. The flag and the chapters commit together with the caller's transaction,
     * and a concurrent recorder waits on the row lock and then records nothing. [chapters] must be indexed 0..n-1 in
     * start order with finite, non-negative times and end >= start.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun recordChaptersIfMissing(
        id: Long,
        chapters: List<VideoChapter>,
    ): Boolean {
        chapters.forEachIndexed { position, chapter ->
            require(chapter.index == position) { "Chapter index ${chapter.index} at position $position" }
            require(chapter.startSeconds.isFinite() && chapter.startSeconds >= 0) { "Invalid chapter start: $chapter" }
            require(chapter.endSeconds.isFinite() && chapter.endSeconds >= chapter.startSeconds) { "Invalid chapter end: $chapter" }
        }
        require(chapters.zipWithNext().all { (a, b) -> a.startSeconds <= b.startSeconds }) { "Chapters are not in start order" }
        if (chapterMapper.markProbedIfMissing(id) == 0) return false
        // Chunked so a file with very many chapters stays within placeholder limits.
        chapters.chunked(CHAPTER_CHUNK_SIZE).forEach { chapterMapper.insert(id, it) }
        logger.info("Recorded chapters, createdFileId=$id, count=${chapters.size}")
        return true
    }

    private companion object {
        const val ID_CHUNK_SIZE = 1000
        const val CHAPTER_CHUNK_SIZE = 500
    }
}

/** Where a playable video is stored; [path] is relative to the share root of [store]. */
data class StreamableVideo(
    val createdFileId: Long,
    val store: NasStore,
    val path: String,
)
