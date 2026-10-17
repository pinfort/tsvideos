package me.pinfort.tsvideos.core.external.database.dto

/** A created_file row together with the executed_file it was split from, for library-wide reads. */
data class ExecutedCreatedFileDto(
    val executedFileId: Long,
    val id: Long,
    val splittedFileId: Long,
    val file: String,
    val size: Long,
    val mime: String?,
    val encoding: String?,
    val status: CreatedFileDto.Status,
) {
    fun toCreatedFileDto(): CreatedFileDto = CreatedFileDto(id, splittedFileId, file, size, mime, encoding, status)
}
