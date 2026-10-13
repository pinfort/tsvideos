package me.pinfort.tsvideos.core.external.samba

import jcifs.SmbResource
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Path

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

    fun getResource(file: String): SmbResource {
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
    fun resourceExists(file: String): Boolean {
        val normalized = file.replace('\\', '/')
        if (videoStoreNas.resolve(normalized).exists()) return true
        return originalStoreNas.resolve(normalized).exists()
    }

    fun deleteResource(file: String): SambaClient.NasType {
        validateFilePath(file)
        val resource = videoStoreNas.resolve(file.replace('\\', '/'))
        if (resource.exists()) {
            check(resource.isFile) { "Refusing to delete a directory" }
            resource.delete()
            return SambaClient.NasType.VIDEO_STORE_NAS
        }
        val originalResource = originalStoreNas.resolve(file.replace('\\', '/'))
        if (originalResource.exists()) {
            check(originalResource.isFile) { "Refusing to delete a directory" }
            originalResource.delete()
            return SambaClient.NasType.ORIGINAL_STORE_NAS
        }
        throw Exception("File not found, path=$file")
    }

    fun moveResource(
        oldFile: String,
        newFile: String,
    ): SambaClient.NasType {
        validateFilePath(oldFile)
        validateFilePath(newFile)
        require(oldFile.replace('\\', '/').lowercase() != newFile.replace('\\', '/').lowercase())
        val candidates =
            listOf(
                videoStoreNas to SambaClient.NasType.VIDEO_STORE_NAS,
                originalStoreNas to SambaClient.NasType.ORIGINAL_STORE_NAS,
            )
        for ((nas, type) in candidates) {
            nas.resolve(oldFile.replace('\\', '/')).use { source ->
                if (!source.exists()) return@use
                check(source.isFile) { "Refusing to move a directory" }
                val size = source.length()
                val modified = source.lastModified()
                createDirectories(newFile, type)
                nas.resolve(newFile.replace('\\', '/')).use { target ->
                    // Exclusive creation closes the existence-check race; copyTo may overwrite.
                    // On copy/readback failure retain source and partial destination for inspection.
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    target
                        .openOutputStream(
                            true,
                            jcifs.SmbConstants.O_WRONLY or jcifs.SmbConstants.O_CREAT or jcifs.SmbConstants.O_EXCL,
                            jcifs.SmbConstants.FILE_WRITE_DATA,
                            jcifs.SmbConstants.FILE_NO_SHARE,
                        ).use { output ->
                            source.openInputStream().use { input ->
                                java.security.DigestInputStream(input, digest).copyTo(output)
                            }
                        }
                    check(target.length() == size) { "Copied size mismatch" }
                    val expected = digest.digest()
                    val actual = java.security.MessageDigest.getInstance("SHA-256")
                    target.openInputStream().use { input ->
                        java.security.DigestInputStream(input, actual).transferTo(java.io.OutputStream.nullOutputStream())
                    }
                    check(expected.contentEquals(actual.digest())) { "Copied checksum mismatch" }
                    // Resolve a fresh handle: jcifs caches attributes on an existing resource.
                    nas.resolve(oldFile.replace('\\', '/')).use { fresh ->
                        check(fresh.length() == size && fresh.lastModified() == modified) { "Source changed during copy" }
                    }
                    source.delete()
                }
                logger.info("Move file, oldFile=$oldFile, newFile=$newFile")
                return type
            }
        }
        throw IllegalStateException("Source file not found")
    }

    private fun validateFilePath(file: String) {
        require(
            file.replace('\\', '/').split('/').all {
                it.isNotBlank() &&
                    it != "." &&
                    it != ".." &&
                    !it.endsWith('.') &&
                    !it.endsWith(' ') &&
                    it.none { c -> c in ":?#%" || c.isISOControl() }
            },
        ) { "Unsafe NAS path" }
    }

    fun uploadResource(
        localFile: File,
        targetFile: String,
        nasType: SambaClient.NasType,
    ): Boolean = uploadResource(localFile, targetFile, nasType, NO_OP_PROGRESS_LISTENER)

    fun uploadResource(
        localFile: File,
        targetFile: String,
        nasType: SambaClient.NasType,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): Boolean {
        val nas =
            when (nasType) {
                SambaClient.NasType.VIDEO_STORE_NAS -> videoStoreNas
                SambaClient.NasType.ORIGINAL_STORE_NAS -> originalStoreNas
            }
        val resource = nas.resolve(targetFile.replace('\\', '/'))
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
        return true
    }

    fun createDirectories(
        file: String,
        nasType: SambaClient.NasType,
    ) {
        val programDirectory = Path.of(file.replace('\\', '/')).parent
        val indexDirectory = programDirectory.parent

        createDirectory(indexDirectory.toString(), nasType)
        createDirectory(programDirectory.toString(), nasType)
    }

    fun createDirectory(
        directory: String,
        nasType: SambaClient.NasType,
    ) {
        when (nasType) {
            SambaClient.NasType.VIDEO_STORE_NAS -> {
                val resource = videoStoreNas.resolve(directory)
                if (!resource.exists()) {
                    logger.info("Create directory, directory=$directory")
                    resource.mkdir()
                }
            }
            SambaClient.NasType.ORIGINAL_STORE_NAS -> {
                val resource = originalStoreNas.resolve(directory)
                if (!resource.exists()) {
                    logger.info("Create directory, directory=$directory")
                    resource.mkdir()
                }
            }
        }
    }
}
