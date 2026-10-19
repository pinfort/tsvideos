package me.pinfort.tsvideos.core.external.samba

import jcifs.SmbConstants
import me.pinfort.tsvideos.core.domain.hex
import me.pinfort.tsvideos.core.domain.sha256Hex
import me.pinfort.tsvideos.core.storage.NasStore
import org.springframework.stereotype.Component
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * All operations address one explicit NAS; no fallback to another share.
 * jcifs failures surface as storage exceptions ([SambaFailures]) with the jcifs exception as the cause, so retry
 * classification still sees network causes and authentication rejections.
 */
@Component
class VerifiedUploadNas(
    private val samba: SambaClient,
    private val directories: NasComponent,
) {
    private fun root(type: NasStore) =
        when (type) {
            NasStore.VIDEO_STORE_NAS -> samba.videoStoreNas()
            NasStore.ORIGINAL_STORE_NAS -> samba.originalStoreNas()
        }

    fun exists(
        type: NasStore,
        path: String,
    ): Boolean = sambaOperation("existence check") { root(type).use { root -> root.resolve(path).use { it.exists() } } }

    fun size(
        type: NasStore,
        path: String,
    ): Long =
        sambaOperation("size check") {
            root(type).use { root ->
                root.resolve(path).use {
                    check(it.exists()) { "Remote artifact missing" }
                    it.length()
                }
            }
        }

    fun sha256(
        type: NasStore,
        path: String,
    ): String =
        sambaOperation("checksum read") {
            root(type).use { root ->
                root.resolve(path).use { resource ->
                    resource.openInputStream().use { it.sha256Hex() }
                }
            }
        }

    /**
     * @return SHA-256 of the bytes actually written, so callers need not re-read the source.
     * Failures of [onOwned] and [progress] propagate unchanged; only SMB failures are translated.
     */
    fun transfer(
        type: NasStore,
        path: String,
        source: File,
        onOwned: () -> Unit,
        progress: (Long, Long) -> Unit,
    ): String {
        directories.createDirectories(path, type)
        return sambaOperation("upload") {
            root(type).use { root ->
                root.resolve(path).use { resource ->
                    resource
                        .openOutputStream(
                            true,
                            SmbConstants.O_WRONLY or SmbConstants.O_CREAT or SmbConstants.O_EXCL,
                            SmbConstants.FILE_WRITE_DATA,
                            SmbConstants.FILE_NO_SHARE,
                        ).use { output ->
                            // A failed/ambiguous open never grants ownership. Persist ownership before writing bytes.
                            onOwned()
                            val digest = MessageDigest.getInstance("SHA-256")
                            DigestInputStream(source.inputStream(), digest).use { input ->
                                // Large writes amortize SMB round trips for multi-GB recordings.
                                val buffer = ByteArray(1 shl 20)
                                val total = source.length()
                                var transferred = 0L
                                progress(0, total)
                                while (true) {
                                    if (Thread.interrupted()) throw InterruptedException("Upload interrupted")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    transferred += count
                                    progress(transferred, total)
                                }
                            }
                            digest.hex()
                        }
                }
            }
        }
    }

    fun finalize(
        type: NasStore,
        temporary: String,
        target: String,
    ) {
        sambaOperation("rename") {
            root(type).use { root ->
                root.resolve(temporary).use { source ->
                    root.resolve(target).use { destination ->
                        check(!destination.exists()) { "Unowned final destination exists" }
                        source.renameTo(destination, false)
                    }
                }
            }
        }
    }

    fun delete(
        type: NasStore,
        path: String,
    ) {
        sambaOperation("delete") { root(type).use { root -> root.resolve(path).use { if (it.exists()) it.delete() } } }
    }
}
