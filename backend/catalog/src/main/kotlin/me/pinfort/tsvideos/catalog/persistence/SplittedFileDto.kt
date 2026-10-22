package me.pinfort.tsvideos.catalog.persistence

import me.pinfort.tsvideos.catalog.domain.SplittedFile

internal data class SplittedFileDto(
    val id: Long,
    val executedFileId: Long,
    val file: String,
    val size: Long,
    val duration: Double,
    val status: Status,
) {
    enum class Status {
        REGISTERED,
        COMPRESS_SAVED,
        ENCODE_TASK_ADDED,
    }

    fun toDomain(): SplittedFile =
        SplittedFile(
            id = id,
            executedFileId = executedFileId,
            file = file,
            size = size,
            duration = duration,
            status =
                when (status) {
                    Status.REGISTERED -> SplittedFile.Status.REGISTERED
                    Status.COMPRESS_SAVED -> SplittedFile.Status.COMPRESS_SAVED
                    Status.ENCODE_TASK_ADDED -> SplittedFile.Status.ENCODE_TASK_ADDED
                },
        )
}
