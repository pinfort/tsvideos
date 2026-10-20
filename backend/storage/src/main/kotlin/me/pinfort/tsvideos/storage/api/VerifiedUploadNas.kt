package me.pinfort.tsvideos.storage.api

import java.io.File

/**
 * Primitive NAS operations for verified uploads. Every operation addresses one explicit [NasStore] with a share-relative
 * path; there is no fallback to another share. Each call opens its own share root and closes it before returning.
 *
 * NAS failures surface as [NasStorageException] with the implementation exception as the cause, so retry classification
 * still sees network causes and authentication rejections. Claims, manifest states and registration belong to callers.
 */
interface VerifiedUploadNas {
    fun exists(
        type: NasStore,
        path: String,
    ): Boolean

    /** @throws IllegalStateException when the file is missing. */
    fun size(
        type: NasStore,
        path: String,
    ): Long

    /** SHA-256 (lowercase hex) of the remote bytes. */
    fun sha256(
        type: NasStore,
        path: String,
    ): String

    /**
     * Creates the parent directories, then creates [path] exclusively (fails if it exists) and copies [source] into it.
     * [onOwned] runs once the exclusive creation succeeded and before any byte is written; a failed or ambiguous open
     * never calls it. Honours thread interruption between writes.
     * @return SHA-256 of the bytes actually written, so callers need not re-read the source.
     * Failures of [onOwned] and [progress] propagate unchanged; only NAS failures are translated.
     */
    fun transfer(
        type: NasStore,
        path: String,
        source: File,
        onOwned: () -> Unit,
        progress: (Long, Long) -> Unit,
    ): String

    /**
     * Renames [temporary] to [target] without replacing an existing file.
     * @throws IllegalStateException when [target] already exists.
     */
    fun finalize(
        type: NasStore,
        temporary: String,
        target: String,
    )

    /** Deletes [path] when it exists. */
    fun delete(
        type: NasStore,
        path: String,
    )
}
