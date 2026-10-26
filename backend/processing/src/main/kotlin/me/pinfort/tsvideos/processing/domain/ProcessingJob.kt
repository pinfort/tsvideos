package me.pinfort.tsvideos.processing.domain

import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime

/** A version fingerprint, not a content hash. Preserve size/mtime when restoring a recording. */
data class ProcessingIdentity(
    val key: String,
    val path: String,
    val size: Long,
    val modifiedMillis: Long,
) {
    companion object {
        fun from(file: File): ProcessingIdentity {
            require(file.isFile) { "Recording is not a regular file" }
            val path =
                file.canonicalFile
                    .toPath()
                    .normalize()
                    .toString()
            val size = file.length()
            val modified = file.lastModified()
            return ProcessingIdentity(hash("$path\u0000$size\u0000$modified"), path, size, modified)
        }

        fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {
                "%02x".format(it)
            }
    }
}

data class ProcessingJob(
    val id: Long,
    val identityKey: String,
    val inputPath: String,
    val inputSize: Long,
    val modifiedMillis: Long,
    val state: State,
    val executedFileId: Long?,
    val callbackClaim: String?,
    val callbackManifest: String? = null,
    // UTC; last change of any column, e.g. when the current callback claim was taken
    val updatedAt: LocalDateTime? = null,
) {
    /**
     * Persisted by name in `processing_job.state`, so renaming a constant requires a data migration.
     * See docs/processing-idempotency.md for the lifecycle.
     */
    enum class State {
        CLAIMED,
        RETRYABLE,
        QUARANTINED,
        SUBMISSION_UNKNOWN,
        SUBMITTED,
        REJECTED,
        COMPLETED,
        RESETTING,
        ;

        /** Only the generic transitions; every other change goes through its own guarded operation. */
        fun canTransitionTo(next: State): Boolean = this to next in ALLOWED_TRANSITIONS

        companion object {
            private val ALLOWED_TRANSITIONS =
                setOf(
                    CLAIMED to RETRYABLE,
                    CLAIMED to SUBMISSION_UNKNOWN,
                )

            /** The encoder acknowledged the submission. */
            val ACCEPTED = setOf(SUBMITTED, COMPLETED)

            /** The encoder may hold the inputs, so its callback may claim or reconcile the job. */
            val CALLBACK = setOf(SUBMITTED, SUBMISSION_UNKNOWN, COMPLETED)

            /** No encoder or invocation can still be using the files. */
            val RESETTABLE = setOf(REJECTED, COMPLETED, RETRYABLE, RESETTING)
        }
    }
}
