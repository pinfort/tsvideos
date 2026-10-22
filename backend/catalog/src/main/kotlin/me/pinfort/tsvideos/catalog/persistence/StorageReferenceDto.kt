package me.pinfort.tsvideos.catalog.persistence

internal data class StorageReferenceDto(
    val programId: Long,
    val programName: String,
    val fileId: Long?,
    val file: String?,
    val size: Long?,
)
