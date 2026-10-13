package me.pinfort.tsvideos.core.domain

/** Durable ownership evidence; paths are exact share-relative paths including baseDir. */
data class UploadManifest(
    val id: Long,
    val jobId: Long,
    val artifactKey: String,
    val destinationKey: String,
    val nasType: String,
    val finalPath: String,
    val temporaryPath: String,
    val sourcePath: String,
    val sourceSize: Long,
    val sourceModified: Long,
    val sourceSha256: String,
    val checksumRequired: Boolean,
    val state: String,
    val claimToken: String?,
    val createdFileId: Long?,
)

/** Receipt is always reloaded and validated before registration or deletion. */
data class VerifiedUploadReceipt(
    val manifestId: Long,
)
