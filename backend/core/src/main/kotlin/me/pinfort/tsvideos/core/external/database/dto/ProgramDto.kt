package me.pinfort.tsvideos.core.external.database.dto

import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramDetail
import java.time.LocalDateTime

data class ProgramDto(
    val id: Long,
    val name: String,
    val executedFileId: Long,
    val status: Status,
    val drops: Int?,
    val size: Long?,
    val recordedAt: LocalDateTime?,
    val channel: String?,
    val title: String?,
    val channelName: String?,
    val duration: Double?,
) {
    enum class Status {
        REGISTERED,
        COMPLETED,
        ERROR,
    }

    private fun toStatus(): Program.Status =
        when (status) {
            Status.REGISTERED -> Program.Status.REGISTERED
            Status.COMPLETED -> Program.Status.COMPLETED
            Status.ERROR -> Program.Status.ERROR
        }

    fun toDomain(): Program =
        Program(
            id = id,
            name = name,
            executedFileId = executedFileId,
            status = toStatus(),
            drops = drops ?: -1,
            size = size ?: 0,
            recordedAt = recordedAt ?: LocalDateTime.MIN,
            channel = channel ?: "",
            title = title ?: "",
            channelName = channelName ?: "",
            duration = duration ?: -1.0,
        )

    fun toProgramDetail(
        createdFiles: List<CreatedFileDto>,
        tags: List<String> = emptyList(),
        checks: List<String> = emptyList(),
    ): ProgramDetail =
        ProgramDetail(
            id = id,
            name = name,
            executedFileId = executedFileId,
            status = toStatus(),
            drops = drops ?: -1,
            size = size ?: 0,
            recordedAt = recordedAt ?: LocalDateTime.MIN,
            channel = channel ?: "",
            title = title ?: "",
            channelName = channelName ?: "",
            duration = duration ?: -1.0,
            createdFiles = createdFiles.map { it.toDomain() },
            tags = tags,
            checks = checks,
        )
}
