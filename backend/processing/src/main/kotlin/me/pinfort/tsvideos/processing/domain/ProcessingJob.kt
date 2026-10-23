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
    val state: String,
    val executedFileId: Long?,
    val callbackClaim: String?,
    val callbackManifest: String? = null,
    // UTC; last change of any column, e.g. when the current callback claim was taken
    val updatedAt: LocalDateTime? = null,
)
