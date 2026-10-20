package me.pinfort.tsvideos.storage.api

import java.io.File

/**
 * NAS operations on recorded created_file paths (share-relative, baseDir included).
 *
 * Lookups that take no [NasStore] search VIDEO_STORE_NAS first, then ORIGINAL_STORE_NAS. Operations throw
 * [NasStorageException] for NAS failures; path validation ([IllegalArgumentException]) and state checks
 * ([IllegalStateException]) propagate unchanged. The implementation keeps one share root per store for its lifetime.
 */
interface NasComponent {
    fun matchesSize(
        file: String,
        expectedBytes: Long,
        nasType: NasStore,
    ): Boolean

    /** Whether [file] exists on either store, in the same order as [deleteResource]; false rather than an exception when absent. */
    fun resourceExists(file: String): Boolean

    /**
     * Deletes the regular file at [file] from the first store that has it (rejecting directories and unsafe paths).
     * @return the store it was deleted from.
     */
    fun deleteResource(file: String): NasStore

    /**
     * Moves [oldFile] to [newFile] on the first store that has it: the destination is created exclusively, verified by
     * size and SHA-256 read-back, and the source is deleted only if it did not change meanwhile. A destination this move
     * created is removed on failure only while the source is confirmed intact.
     * @return the store the file was moved on.
     */
    fun moveResource(
        oldFile: String,
        newFile: String,
    ): NasStore

    fun uploadResource(
        localFile: File,
        targetFile: String,
        nasType: NasStore,
    ): Boolean = uploadResource(localFile, targetFile, nasType) { _, _ -> }

    /** Skips (false) when [targetFile] already exists; otherwise creates its directories and copies the file (true). */
    fun uploadResource(
        localFile: File,
        targetFile: String,
        nasType: NasStore,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): Boolean

    /** Creates the program directory of [file] and its index directory when missing. */
    fun createDirectories(
        file: String,
        nasType: NasStore,
    )

    fun createDirectory(
        directory: String,
        nasType: NasStore,
    )

    /**
     * Opens [file] on [store] only (no fallback to the other share) for reading, with a fresh share root per call.
     * The caller owns the returned handle and must close it; if opening fails, the root and file are released here.
     * @throws NasStorageException when the share or the file cannot be reached or the login is rejected;
     *   other failures propagate unchanged.
     */
    fun openForRead(
        store: NasStore,
        file: String,
    ): NasReadHandle
}
