package me.pinfort.tsvideos.core.component

import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPOutputStream

@Component
class CompressComponent(
    private val logger: Logger,
) {
    companion object {
        private const val COMPRESS_BUFFER_SIZE = 8 * 1024
        private val NO_OP_PROGRESS_LISTENER: (Long, Long) -> Unit = { _, _ -> }
    }

    // returns false (and logs, without throwing) if compressedFile already exists and force is not set
    fun compress(
        originalFile: File,
        compressedFile: File,
        force: Boolean = false,
    ): Boolean = compress(originalFile, compressedFile, force, NO_OP_PROGRESS_LISTENER)

    fun compress(
        originalFile: File,
        compressedFile: File,
        force: Boolean,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): Boolean {
        if (compressedFile.exists() && !force) {
            logger.error("Compressed file already exists, compressedFile=$compressedFile")
            return false
        }
        val totalBytes = originalFile.length()
        // CREATE_NEW makes ownership explicit even if another invocation races the existence check.
        val options =
            if (force) {
                arrayOf(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
            } else {
                arrayOf(StandardOpenOption.CREATE_NEW)
            }
        val output = Files.newOutputStream(compressedFile.toPath(), *options)
        try {
            GZIPOutputStream(output).use { target ->
                originalFile.inputStream().buffered().use { input ->
                    val buffer = ByteArray(COMPRESS_BUFFER_SIZE)
                    var transferred = 0L
                    onProgress(transferred, totalBytes)
                    while (true) {
                        if (Thread.interrupted()) throw InterruptedException("Compression interrupted")
                        val read = input.read(buffer)
                        if (read == -1) break
                        target.write(buffer, 0, read)
                        transferred += read
                        onProgress(transferred, totalBytes)
                    }
                }
            }
        } catch (failure: Exception) {
            // Never remove a preexisting force-overwritten target: ownership is not ours.
            if (!force) {
                try {
                    Files.deleteIfExists(compressedFile.toPath())
                } catch (cleanup: Exception) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        } finally {
            output.close()
        }
        return true
    }
}
