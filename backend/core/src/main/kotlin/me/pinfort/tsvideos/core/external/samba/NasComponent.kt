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

    fun matchesSize(
        file: String,
        expectedBytes: Long,
        nasType: SambaClient.NasType,
    ): Boolean {
        val nas =
            when (nasType) {
                SambaClient.NasType.VIDEO_STORE_NAS -> videoStoreNas
                SambaClient.NasType.ORIGINAL_STORE_NAS -> originalStoreNas
            }
        return nas.resolve(file.replace('\\', '/')).use { it.exists() && it.length() == expectedBytes }
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
        val resource = videoStoreNas.resolve(file.replace('\\', '/'))
        if (resource.exists()) {
            resource.delete()
            return SambaClient.NasType.VIDEO_STORE_NAS
        }
        val originalResource = originalStoreNas.resolve(file.replace('\\', '/'))
        if (originalResource.exists()) {
            originalResource.delete()
            return SambaClient.NasType.ORIGINAL_STORE_NAS
        }
        throw Exception("File not found, path=$file")
    }

    fun moveResource(
        oldFile: String,
        newFile: String,
    ): SambaClient.NasType {
        val resource = videoStoreNas.resolve(oldFile.replace('\\', '/'))
        if (resource.exists()) {
            createDirectories(newFile, SambaClient.NasType.VIDEO_STORE_NAS)
            resource.copyTo(videoStoreNas.resolve(newFile.replace('\\', '/')))
            resource.delete()
            logger.info("Move file, oldFile=$oldFile, newFile=$newFile")
            return SambaClient.NasType.VIDEO_STORE_NAS
        }
        val originalResource = originalStoreNas.resolve(oldFile.replace('\\', '/'))
        if (originalResource.exists()) {
            createDirectories(newFile, SambaClient.NasType.ORIGINAL_STORE_NAS)
            originalResource.copyTo(originalStoreNas.resolve(newFile.replace('\\', '/')))
            originalResource.delete()
            logger.info("Move file, oldFile=$oldFile, newFile=$newFile")
            return SambaClient.NasType.ORIGINAL_STORE_NAS
        }
        throw Exception("File not found, path=$oldFile")
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
