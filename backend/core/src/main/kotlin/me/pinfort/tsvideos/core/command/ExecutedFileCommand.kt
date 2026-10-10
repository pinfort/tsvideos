package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.ExecutedFile
import me.pinfort.tsvideos.core.external.database.mapper.ExecutedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.GeneratedKeyHolder
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class ExecutedFileCommand(
    private val executedFileMapper: ExecutedFileMapper,
    private val executedFileTagCommand: ExecutedFileTagCommand,
    private val logger: Logger,
) {
    fun find(id: Long): ExecutedFile? = executedFileMapper.find(id)?.toDomain()

    fun findByFile(file: String): ExecutedFile? = executedFileMapper.selectByFile(file).firstOrNull()?.toDomain()

    fun insert(
        file: String,
        drops: Int,
        size: Long,
        recordedAt: LocalDateTime,
        channel: String,
        title: String,
        channelName: String,
        duration: Double,
        dryRun: Boolean = false,
    ): ExecutedFile {
        val status = ExecutedFile.Status.DROPCHECKED
        val id =
            if (!dryRun) {
                val keyHolder = GeneratedKeyHolder()
                executedFileMapper.insert(file, drops, size, recordedAt, channel, title, channelName, duration, status.name, keyHolder)
                keyHolder.id
            } else {
                0L
            }
        val executedFile =
            ExecutedFile(
                id = id,
                file = file,
                drops = drops,
                size = size,
                recordedAt = recordedAt,
                channel = channel,
                title = title,
                channelName = channelName,
                duration = duration,
                status = status,
            )
        logger.info("Insert executed file, id=$id, executedFile=$executedFile")
        return executedFile
    }

    fun updateStatus(
        executedFile: ExecutedFile,
        status: ExecutedFile.Status,
        dryRun: Boolean = false,
    ): ExecutedFile {
        if (!dryRun) {
            executedFileMapper.updateStatus(executedFile.id, status.name)
        }
        val updated = executedFile.copy(status = status)
        logger.info("Update executed file status, id=${executedFile.id}, status=$status")
        return updated
    }

    fun delete(
        executedFile: ExecutedFile,
        dryRun: Boolean = false,
    ) {
        executedFileTagCommand.deleteByExecutedFileId(executedFile.id, dryRun)
        if (!dryRun) {
            executedFileMapper.delete(executedFile.id)
        }
        logger.info("Delete executed file, id=${executedFile.id}, executedFile=$executedFile")
    }
}
