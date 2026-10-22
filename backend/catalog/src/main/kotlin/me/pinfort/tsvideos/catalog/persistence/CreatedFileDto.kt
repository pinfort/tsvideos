package me.pinfort.tsvideos.catalog.persistence

import me.pinfort.tsvideos.catalog.domain.CreatedFile

internal data class CreatedFileDto(
    val id: Long,
    val splittedFileId: Long,
    val file: String,
    val size: Long,
    val mime: String?,
    val encoding: String?,
    val status: Status,
    val width: Int? = null,
    val height: Int? = null,
) {
    enum class Status {
        REGISTERED,
        ENCODE_SUCCESS,
        FILE_MOVED,
    }

    fun toDomain(): CreatedFile =
        CreatedFile(
            id = id,
            splittedFileId = splittedFileId,
            file = file,
            size = size,
            mime = mime,
            encoding = encoding,
            status =
                when (status) {
                    Status.REGISTERED -> CreatedFile.Status.REGISTERED
                    Status.ENCODE_SUCCESS -> CreatedFile.Status.ENCODE_SUCCESS
                    Status.FILE_MOVED -> CreatedFile.Status.FILE_MOVED
                },
            width = width,
            height = height,
        )
}
