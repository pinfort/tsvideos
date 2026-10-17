package me.pinfort.tsvideos.core.command

import jcifs.CIFSException
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.GeneratedKeyHolder
import me.pinfort.tsvideos.core.external.samba.NasComponent
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.core.external.samba.SmbFileResource
import org.slf4j.Logger
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class CreatedFileCommand(
    private val createdFileMapper: CreatedFileMapper,
    private val sambaClient: SambaClient,
    private val nasComponent: NasComponent,
    private val logger: Logger,
) {
    fun selectBySplittedFileId(splittedFileId: Long): List<CreatedFile> =
        createdFileMapper.selectBySplittedFileId(splittedFileId).map { it.toDomain() }

    fun insert(
        splittedFileId: Long,
        file: String,
        size: Long,
        mime: String?,
        encoding: String?,
        status: CreatedFile.Status = CreatedFile.Status.FILE_MOVED,
        dryRun: Boolean = false,
    ): CreatedFile {
        val id =
            if (!dryRun) {
                val keyHolder = GeneratedKeyHolder()
                createdFileMapper.insert(splittedFileId, file, size, mime, encoding, status.name, keyHolder)
                keyHolder.id
            } else {
                0L
            }
        val createdFile =
            CreatedFile(
                id = id,
                splittedFileId = splittedFileId,
                file = file,
                size = size,
                mime = mime,
                encoding = encoding,
                status = status,
            )
        logger.info("Insert created file, id=$id, createdFile=$createdFile")
        return createdFile
    }

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

    fun findMp4File(id: Long): CreatedFile? {
        val createdFile: CreatedFile = createdFileMapper.find(id)?.toDomain() ?: return null
        // 動画ファイルでない場合はファイルが存在しない扱いをする
        if (!createdFile.isMp4) return null
        return createdFile
    }

    fun streamCreatedFile(id: Long): Resource? {
        val createdFile: CreatedFile = findMp4File(id) ?: return null
        return try {
            val resource =
                sambaClient
                    .videoStoreNas()
                    .resolve(createdFile.file.replace('\\', '/'))
            SmbFileResource(resource, resource.length())
        } catch (e: CIFSException) {
            null
        }
    }

    @Transactional
    fun delete(
        createdFile: CreatedFile,
        dryRun: Boolean = false,
    ): SambaClient.NasType =
        try {
            val removedFrom =
                if (!dryRun) {
                    createdFileMapper.delete(createdFile.id)
                    nasComponent.deleteResource(createdFile.file)
                } else {
                    SambaClient.NasType.VIDEO_STORE_NAS
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

    @Transactional
    fun move(
        createdFile: CreatedFile,
        newFile: String,
        dryRun: Boolean = false,
    ): SambaClient.NasType =
        try {
            val movedFrom =
                if (!dryRun) {
                    createdFileMapper.updateFile(createdFile.id, newFile)
                    nasComponent.moveResource(createdFile.file, newFile)
                } else {
                    SambaClient.NasType.VIDEO_STORE_NAS
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
}
