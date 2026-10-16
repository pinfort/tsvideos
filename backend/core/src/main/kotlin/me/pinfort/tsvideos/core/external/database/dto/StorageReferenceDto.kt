package me.pinfort.tsvideos.core.external.database.dto

data class StorageReferenceDto(
    val programId: Long,
    val programName: String,
    val fileId: Long?,
    val file: String?,
    val size: Long?,
)
