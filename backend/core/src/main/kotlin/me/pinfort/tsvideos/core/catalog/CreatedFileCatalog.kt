package me.pinfort.tsvideos.core.catalog

import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.VideoResolution
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.GeneratedKeyHolder
import me.pinfort.tsvideos.core.storage.NasStore
import org.slf4j.Logger
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Catalog read, registration and metadata-update operations on `created_file` rows. No NAS access and no
 * HTTP types: deleting and moving a file together with its NAS copy stay coordinated operations of
 * [me.pinfort.tsvideos.core.command.CreatedFileCommand].
 *
 * Reads join the caller's transaction when there is one. Writes are `MANDATORY`: they never open or commit a
 * transaction of their own, so the caller decides what commits (or rolls back) together with them.
 */
@Component
class CreatedFileCatalog(
    private val createdFileMapper: CreatedFileMapper,
    private val logger: Logger,
) {
    fun find(id: Long): CreatedFile? = createdFileMapper.find(id)?.toDomain()

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
}

/** Where a playable video is stored; [path] is relative to the share root of [store]. */
data class StreamableVideo(
    val createdFileId: Long,
    val store: NasStore,
    val path: String,
)
