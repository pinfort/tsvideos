package me.pinfort.tsvideos.catalog.api

import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.VideoResolution
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
        createdFileMapper.insert(
            splittedFileId,
            file,
            size,
            mime,
            encoding,
            status.name,
            keyHolder,
            resolution?.width,
            resolution?.height,
        )
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

    private companion object {
        const val ID_CHUNK_SIZE = 1000
    }
}

/** Where a playable video is stored; [path] is relative to the share root of [store]. */
data class StreamableVideo(
    val createdFileId: Long,
    val store: NasStore,
    val path: String,
)
