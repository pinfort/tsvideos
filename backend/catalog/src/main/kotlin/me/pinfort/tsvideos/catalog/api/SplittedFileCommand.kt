package me.pinfort.tsvideos.catalog.api

import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.catalog.persistence.GeneratedKeyHolder
import me.pinfort.tsvideos.catalog.persistence.SplittedFileMapper
import org.slf4j.Logger
import org.springframework.stereotype.Component

@Component
class SplittedFileCommand internal constructor(
    private val splittedFileMapper: SplittedFileMapper,
    private val logger: Logger,
) {
    fun selectByExecutedFileId(executedFileId: Long): List<SplittedFile> =
        splittedFileMapper.selectByExecutedFileId(executedFileId).map { it.toDomain() }

    fun findByFile(file: String): SplittedFile? = splittedFileMapper.selectByFile(file).firstOrNull()?.toDomain()

    fun insert(
        executedFileId: Long,
        file: String,
        size: Long,
        duration: Double,
        dryRun: Boolean = false,
    ): SplittedFile {
        val status = SplittedFile.Status.REGISTERED
        val id =
            if (!dryRun) {
                val keyHolder = GeneratedKeyHolder()
                splittedFileMapper.insert(executedFileId, file, size, duration, status.name, keyHolder)
                keyHolder.id
            } else {
                0L
            }
        val splittedFile =
            SplittedFile(
                id = id,
                executedFileId = executedFileId,
                file = file,
                size = size,
                duration = duration,
                status = status,
            )
        logger.info("Insert splitted file, id=$id, splittedFile=$splittedFile")
        return splittedFile
    }

    fun updateStatus(
        splittedFile: SplittedFile,
        status: SplittedFile.Status,
        dryRun: Boolean = false,
    ): SplittedFile {
        if (!dryRun) {
            splittedFileMapper.updateStatus(splittedFile.id, status.name)
        }
        val updated = splittedFile.copy(status = status)
        logger.info("Update splitted file status, id=${splittedFile.id}, status=$status")
        return updated
    }

    fun delete(
        splittedFile: SplittedFile,
        dryRun: Boolean = false,
    ) {
        if (!dryRun) {
            splittedFileMapper.delete(splittedFile.id)
        }
        logger.info("Delete splitted file, id=${splittedFile.id}, splittedFile=$splittedFile")
    }
}
