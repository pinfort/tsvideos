package me.pinfort.tsvideos.catalog.api

import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.persistence.CreatedFileMapper
import me.pinfort.tsvideos.storage.api.NasComponent
import me.pinfort.tsvideos.storage.api.NasStore
import org.slf4j.Logger
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Coordinated delete/move of `created_file` rows together with their NAS copies. Registration, metadata updates and
 * playback lookups are [CreatedFileCatalog]; manager:api opens the NAS for playback.
 */
@Component
class CreatedFileCommand internal constructor(
    private val createdFileMapper: CreatedFileMapper,
    private val nasComponent: NasComponent,
    private val logger: Logger,
) {
    fun selectBySplittedFileId(splittedFileId: Long): List<CreatedFile> =
        createdFileMapper.selectBySplittedFileId(splittedFileId).map { it.toDomain() }

    fun updateStatus(
        createdFile: CreatedFile,
        status: CreatedFile.Status,
        dryRun: Boolean = false,
    ): CreatedFile {
        if (!dryRun) {
            createdFileMapper.updateStatus(createdFile.id, status.name)
        }
        val updated = createdFile.copy(status = status)
        logger.info("Update created file status, id=${createdFile.id}, status=$status")
        return updated
    }

    @Transactional
    fun delete(
        createdFile: CreatedFile,
        dryRun: Boolean = false,
    ): NasStore =
        try {
            val removedFrom =
                if (!dryRun) {
                    createdFileMapper.delete(createdFile.id)
                    nasComponent.deleteResource(createdFile.file)
                } else {
                    NasStore.VIDEO_STORE_NAS
                }
            logger.info("Delete created file, id=${createdFile.id}, createdFile=$createdFile, removedFrom=$removedFrom")
            removedFrom
        } catch (e: Exception) {
            logger.error("Failed to delete file. id=${createdFile.id}, file=${createdFile.file}, createdFile=$createdFile", e)
            // RuntimeExceptionにしないとロールバック対象にならない
            throw RuntimeException(e)
        }

    /**
     * Best-effort reverse of NAS moves that completed before a later file failed, newest first.
     * Uses the same verified move, so a failed restore keeps the moved copy rather than losing it.
     * Only files whose forward move fully completed are listed; a move that failed cleans up after itself.
     */
    fun restoreMoved(moved: List<Pair<String, String>>) {
        moved.asReversed().forEach { (oldFile, newFile) ->
            try {
                nasComponent.moveResource(newFile, oldFile)
                logger.info("Restored moved file, newFile=$newFile, oldFile=$oldFile")
            } catch (e: Exception) {
                logger.error("Failed to restore moved file; reconcile manually, newFile=$newFile, oldFile=$oldFile", e)
            }
        }
    }

    /**
     * Verified NAS move of [createdFile] to [newFile]. The row is changed separately by [recordMove]: ProgramCommand
     * moves every NAS file of a program first and records the rows last (see ProgramCommand.moveCreatedFiles).
     */
    fun moveResource(
        createdFile: CreatedFile,
        newFile: String,
        dryRun: Boolean = false,
    ): NasStore =
        try {
            val movedFrom =
                if (!dryRun) {
                    nasComponent.moveResource(createdFile.file, newFile)
                } else {
                    NasStore.VIDEO_STORE_NAS
                }
            logger.info("Move created file, id=${createdFile.id}, newFile=$newFile, createdFile=$createdFile, movedFrom=$movedFrom")
            movedFrom
        } catch (e: Exception) {
            logger.error(
                "Failed to move file. id=${createdFile.id}, file=${createdFile.file}, newFile=$newFile, createdFile=$createdFile",
                e,
            )
            // RuntimeExceptionにしないとロールバック対象にならない
            throw RuntimeException(e)
        }

    /** Points the row of [createdFile] at [newFile], claiming the path guard of [newFile] first like every path write. */
    @Transactional
    fun recordMove(
        createdFile: CreatedFile,
        newFile: String,
        dryRun: Boolean = false,
    ) {
        if (!dryRun) {
            val pathKey = CreatedFilePathKey.hash(newFile)
            createdFileMapper.lockPathGuard(pathKey)
            createdFileMapper.updateFile(createdFile.id, newFile, pathKey)
        }
        logger.info("Record moved created file, id=${createdFile.id}, newFile=$newFile")
    }
}
