package me.pinfort.tsvideos.core.external.samba

import jcifs.SmbConstants
import jcifs.SmbResource
import me.pinfort.tsvideos.core.storage.NasStore
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File
import java.io.OutputStream
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

/** Public operations throw storage exceptions ([SambaFailures]) instead of jcifs ones. */
@Component
class NasComponent(
    private val sambaClient: SambaClient,
    private val logger: Logger,
) {
    private val videoStoreNas = sambaClient.videoStoreNas()
    private val originalStoreNas = sambaClient.originalStoreNas()

    companion object {
        private const val UPLOAD_BUFFER_SIZE = 8 * 1024
        private val NO_OP_PROGRESS_LISTENER: (Long, Long) -> Unit = { _, _ -> }
    }

    fun matchesSize(
        file: String,
        expectedBytes: Long,
        nasType: NasStore,
    ): Boolean =
        sambaOperation("size check") {
            store(nasType).resolve(file.replace('\\', '/')).use { it.exists() && it.length() == expectedBytes }
        }

    // Internal so the jcifs handle stays inside storage; it has no production caller (removal is module-boundaries U4).
    internal fun getResource(file: String): SmbResource {
        // replace because windows path using '\'
        val resource = videoStoreNas.resolve(file.replace('\\', '/'))
        if (resource.exists()) return resource
        val originalResource = originalStoreNas.resolve(file.replace('\\', '/'))
        if (originalResource.exists()) return originalResource
        throw Exception("File not found, path=$file")
    }

    /**
     * created_file の file は VIDEO_STORE_NAS / ORIGINAL_STORE_NAS のどちらにも存在しうるため、
     * getResource と同じ順序で両方を確認する。見つからない場合は例外ではなく false を返す。
     */
    fun resourceExists(file: String): Boolean =
        sambaOperation("existence check") {
            val normalized = file.replace('\\', '/')
            videoStoreNas.resolve(normalized).exists() || originalStoreNas.resolve(normalized).exists()
        }

    fun deleteResource(file: String): NasStore {
        validateFilePath(file)
        return sambaOperation("delete") {
            val resource = videoStoreNas.resolve(file.replace('\\', '/'))
            if (resource.exists()) {
                check(resource.isFile) { "Refusing to delete a directory" }
                resource.delete()
                return NasStore.VIDEO_STORE_NAS
            }
            val originalResource = originalStoreNas.resolve(file.replace('\\', '/'))
            if (originalResource.exists()) {
                check(originalResource.isFile) { "Refusing to delete a directory" }
                originalResource.delete()
                return NasStore.ORIGINAL_STORE_NAS
            }
            throw Exception("File not found, path=$file")
        }
    }

    fun moveResource(
        oldFile: String,
        newFile: String,
    ): NasStore {
        validateFilePath(oldFile)
        validateFilePath(newFile)
        require(oldFile.replace('\\', '/').lowercase(Locale.ROOT) != newFile.replace('\\', '/').lowercase(Locale.ROOT)) {
            "Source and destination are the same path"
        }
        val candidates =
            listOf(
                videoStoreNas to NasStore.VIDEO_STORE_NAS,
                originalStoreNas to NasStore.ORIGINAL_STORE_NAS,
            )
        // The partial-copy cleanup below sees the raw failure; only the exception leaving the move is translated.
        val movedFrom = sambaOperation("move") { moveFirstFound(candidates, oldFile, newFile) }
        if (movedFrom != null) return movedFrom
        logger.warn("Move source not found, oldFile=$oldFile")
        throw IllegalStateException("Source file not found, path=$oldFile")
    }

    private fun moveFirstFound(
        candidates: List<Pair<SmbResource, NasStore>>,
        oldFile: String,
        newFile: String,
    ): NasStore? {
        for ((nas, type) in candidates) {
            nas.resolve(oldFile.replace('\\', '/')).use { source ->
                if (!source.exists()) return@use
                check(source.isFile) { "Refusing to move a directory" }
                val size = source.length()
                val modified = source.lastModified()
                createDirectories(newFile, type)
                nas.resolve(newFile.replace('\\', '/')).use { target ->
                    // Exclusive creation closes the existence-check race; copyTo may overwrite.
                    var created = false
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        target
                            .openOutputStream(
                                true,
                                SmbConstants.O_WRONLY or SmbConstants.O_CREAT or SmbConstants.O_EXCL,
                                SmbConstants.FILE_WRITE_DATA,
                                SmbConstants.FILE_NO_SHARE,
                            ).use { output ->
                                created = true
                                source.openInputStream().use { input ->
                                    DigestInputStream(input, digest).copyTo(output)
                                }
                            }
                        check(target.length() == size) { "Copied size mismatch" }
                        val expected = digest.digest()
                        val actual = MessageDigest.getInstance("SHA-256")
                        target.openInputStream().use { input ->
                            DigestInputStream(input, actual).transferTo(OutputStream.nullOutputStream())
                        }
                        check(expected.contentEquals(actual.digest())) { "Copied checksum mismatch" }
                        check(sourceUnchanged(nas, oldFile, size, modified)) { "Source changed during copy" }
                        source.delete()
                    } catch (e: Exception) {
                        if (created) discardCopy(nas, oldFile, newFile, size, modified, target)
                        throw e
                    }
                }
                logger.info("Move file, oldFile=$oldFile, newFile=$newFile")
                return type
            }
        }
        return null
    }

    // Resolve a fresh handle: jcifs caches attributes on an existing resource.
    private fun sourceUnchanged(
        nas: SmbResource,
        oldFile: String,
        size: Long,
        modified: Long,
    ): Boolean =
        nas.resolve(oldFile.replace('\\', '/')).use { fresh ->
            fresh.exists() && fresh.length() == size && fresh.lastModified() == modified
        }

    /**
     * Removes the destination this move created exclusively, but only while the source is confirmed intact,
     * so a retry is not blocked by a leftover copy. If the source cannot be confirmed (it changed, vanished
     * after a partial delete, or the NAS is unreachable) the destination may be the only copy and is kept.
     */
    private fun discardCopy(
        nas: SmbResource,
        oldFile: String,
        newFile: String,
        size: Long,
        modified: Long,
        target: SmbResource,
    ) {
        val intact = runCatching { sourceUnchanged(nas, oldFile, size, modified) }.getOrDefault(false)
        if (!intact) {
            logger.error("Move failed and the source could not be confirmed intact; kept destination, oldFile=$oldFile, newFile=$newFile")
            return
        }
        runCatching { target.delete() }
            .onSuccess { logger.info("Move failed; removed its partial destination, oldFile=$oldFile, newFile=$newFile") }
            .onFailure { logger.error("Move failed and its partial destination could not be removed, newFile=$newFile", it) }
    }

    // Structural checks only. jcifs resolves '#', '%' (including "%2e%2e%2f") and '?' verbatim,
    // and registered recordings use such names, so refusing them here would block deleting them.
    // ':' is refused because SMB treats it as an alternate data stream separator.
    // BulkLibraryCommand.safePath is deliberately stricter for bulk actions; keep the two in sync knowingly.
    private fun validateFilePath(file: String) {
        val path = file.replace('\\', '/')
        require(
            !path.startsWith('/') &&
                path.split('/').all {
                    it.isNotBlank() && it != "." && it != ".." && it.none { c -> c == ':' || c.isISOControl() }
                },
        ) { "Unsafe NAS path" }
    }

    fun uploadResource(
        localFile: File,
        targetFile: String,
        nasType: NasStore,
    ): Boolean = uploadResource(localFile, targetFile, nasType, NO_OP_PROGRESS_LISTENER)

    fun uploadResource(
        localFile: File,
        targetFile: String,
        nasType: NasStore,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): Boolean =
        sambaOperation("upload") {
            val resource = store(nasType).resolve(targetFile.replace('\\', '/'))
            if (resource.exists()) {
                logger.info("Upload skipped, file already exists, targetFile=$targetFile")
                return false
            }
            createDirectories(targetFile, nasType)
            val totalBytes = localFile.length()
            resource.openOutputStream().use { out ->
                localFile.inputStream().buffered().use { input ->
                    val buffer = ByteArray(UPLOAD_BUFFER_SIZE)
                    var transferred = 0L
                    onProgress(transferred, totalBytes)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        transferred += read
                        onProgress(transferred, totalBytes)
                    }
                }
            }
            logger.info("Upload file, localFile=$localFile, targetFile=$targetFile")
            true
        }

    fun createDirectories(
        file: String,
        nasType: NasStore,
    ) {
        val programDirectory = Path.of(file.replace('\\', '/')).parent
        val indexDirectory = programDirectory.parent

        createDirectory(indexDirectory.toString(), nasType)
        createDirectory(programDirectory.toString(), nasType)
    }

    fun createDirectory(
        directory: String,
        nasType: NasStore,
    ) {
        sambaOperation("create directory") {
            val resource = store(nasType).resolve(directory)
            if (!resource.exists()) {
                logger.info("Create directory, directory=$directory")
                resource.mkdir()
            }
        }
    }

    /**
     * Opens [file] on [store] only (no fallback to the other share) for reading, with a fresh share root per call.
     * @throws me.pinfort.tsvideos.core.storage.NasStorageException when the share or the file cannot be reached or the login is rejected;
     *   other failures propagate unchanged.
     */
    fun openForRead(
        store: NasStore,
        file: String,
    ): SmbFileResource =
        sambaOperation("open for reading") {
            val resource = sambaClient.root(store).resolve(file.replace('\\', '/'))
            SmbFileResource(resource, resource.length())
        }

    private fun store(type: NasStore): SmbResource =
        when (type) {
            NasStore.VIDEO_STORE_NAS -> videoStoreNas
            NasStore.ORIGINAL_STORE_NAS -> originalStoreNas
        }
}
