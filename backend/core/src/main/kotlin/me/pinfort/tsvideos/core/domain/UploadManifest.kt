package me.pinfort.tsvideos.core.domain

import me.pinfort.tsvideos.core.external.samba.SambaClient

/** Durable ownership evidence; paths are exact share-relative paths including baseDir. */
data class UploadManifest(
    val id: Long,
    val jobId: Long,
    val artifactKey: String,
    val destinationKey: String,
    val nasType: SambaClient.NasType,
    val finalPath: String,
    val temporaryPath: String,
    val sourcePath: String,
    val sourceSize: Long,
    val sourceModified: Long,
    val sourceSha256: String,
    val checksumRequired: Boolean,
    val state: State,
    val claimToken: String?,
    val createdFileId: Long?,
) {
    /** Persisted by name; see docs/verified-uploads.md for the transition order. */
    enum class State {
        NEW,
        CREATING,
        TRANSFERRING,
        TEMP_VERIFIED,
        FINALIZING,
        FINALIZED,
        REGISTERED,
        ;

        companion object {
            val VERIFIED = setOf(FINALIZED, REGISTERED)
            val AMBIGUOUS = setOf(CREATING, FINALIZING)
            val OWNED_TEMPORARY = setOf(TRANSFERRING, TEMP_VERIFIED)
        }
    }
}

/** Receipt is always reloaded and validated before registration or deletion. */
data class VerifiedUploadReceipt(
    val manifestId: Long,
)
