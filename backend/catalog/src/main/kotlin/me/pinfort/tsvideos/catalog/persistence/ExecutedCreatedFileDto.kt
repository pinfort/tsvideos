package me.pinfort.tsvideos.catalog.persistence

import me.pinfort.tsvideos.catalog.domain.CreatedFile

/** A created_file row together with the executed_file it was split from, for library-wide reads. */
internal data class ExecutedCreatedFileDto(
    val executedFileId: Long,
    val id: Long,
    val splittedFileId: Long,
    val file: String,
    val size: Long,
    val mime: String?,
    val encoding: String?,
    val status: CreatedFileDto.Status,
    val width: Int? = null,
    val height: Int? = null,
) {
    fun toCreatedFileDto(): CreatedFileDto = CreatedFileDto(id, splittedFileId, file, size, mime, encoding, status, width, height)

    fun toDomain(): CreatedFile = toCreatedFileDto().toDomain()
}
