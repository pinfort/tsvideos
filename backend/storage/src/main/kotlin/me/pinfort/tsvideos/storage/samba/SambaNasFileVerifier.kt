package me.pinfort.tsvideos.storage.samba

import jcifs.SmbResource
import me.pinfort.tsvideos.storage.api.NasFileVerifier
import me.pinfort.tsvideos.storage.api.NasFileVerifier.Result
import me.pinfort.tsvideos.storage.api.NasFileVerifier.Status
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Read-only metadata checks, in the same store order as playback/cleanup resolution. */
@Component
internal class SambaNasFileVerifier(
    private val sambaClient: SambaClient,
) : NasFileVerifier {
    /** Opens each store root at most once for all checks made inside [block]. */
    override fun <T> session(block: (NasFileVerifier.Session) -> T): T = Session().use(block)

    inner class Session : NasFileVerifier.Session {
        private val opened = mutableListOf<SmbResource>()
        private val stores =
            listOf(sambaClient::videoStoreNas, sambaClient::originalStoreNas).map { open ->
                // A failed open is remembered, so an unreachable store is not retried for every file.
                lazy {
                    runCatching { open() }
                        .onSuccess { opened += it }
                        .onFailure {
                            if (it is InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw it
                            }
                            logger.warn("Opening NAS store failed: {}", it.javaClass.name)
                        }
                }
            }

        override fun verify(
            file: String,
            expectedBytes: Long,
        ): Result {
            val normalized = file.replace('\\', '/')
            // Recorded paths are relative to the SMB share. A ':' would be a drive letter, a URL scheme or
            // an NTFS stream, none of which can name a share file, so checking it could only yield a false MISSING.
            if (expectedBytes < 0 ||
                normalized.isBlank() ||
                normalized.startsWith('/') ||
                ':' in normalized ||
                normalized.split('/').any { it.isEmpty() || it == "." || it == ".." }
            ) {
                return Result(Status.UNCHECKED, reason = "Invalid NAS path or recorded size")
            }
            // If a higher-priority store cannot be checked, do not claim a fallback is the resolved file.
            return try {
                for (store in stores) {
                    // The open failure was logged once; later files only report it.
                    val root =
                        store.value.getOrElse {
                            return Result(
                                Status.INACCESSIBLE,
                                reason = "NAS metadata could not be read",
                            )
                        }
                    root.resolve(normalized).use { resource ->
                        if (resource.exists()) {
                            if (!resource.isFile) return Result(Status.UNCHECKED, reason = "Path is not a regular file")
                            val size = resource.length()
                            return Result(
                                if (size == expectedBytes) Status.VERIFIED else Status.SIZE_MISMATCH,
                                size,
                            )
                        }
                    }
                }
                Result(Status.MISSING, reason = "Absent from both NAS stores")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: Exception) {
                // Exception messages can contain server URLs and credentials, so only the class is logged.
                logger.warn("NAS metadata check failed: {}", error.javaClass.name)
                Result(Status.INACCESSIBLE, reason = "NAS metadata could not be read")
            }
        }

        override fun close() {
            opened.forEach { root ->
                try {
                    root.close()
                } catch (error: Exception) {
                    logger.warn("Closing NAS store failed: {}", error.javaClass.name)
                }
            }
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SambaNasFileVerifier::class.java)
    }
}
