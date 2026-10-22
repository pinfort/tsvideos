package me.pinfort.tsvideos.catalog.persistence

import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import java.time.LocalDateTime

internal data class ExecutedFileDto(
    val id: Long,
    val file: String,
    val drops: Int,
    val size: Long,
    val recordedAt: LocalDateTime,
    val channel: String,
    val title: String,
    val channelName: String,
    val duration: Double,
    val status: Status,
) {
    enum class Status {
        REGISTERED,
        DROPCHECKED,
        SPLITTED,
    }

    fun toDomain(): ExecutedFile =
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
            status =
                when (status) {
                    Status.REGISTERED -> ExecutedFile.Status.REGISTERED
                    Status.DROPCHECKED -> ExecutedFile.Status.DROPCHECKED
                    Status.SPLITTED -> ExecutedFile.Status.SPLITTED
                },
        )
}
