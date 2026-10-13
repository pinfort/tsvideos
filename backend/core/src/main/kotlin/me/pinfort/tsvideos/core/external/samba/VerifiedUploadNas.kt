package me.pinfort.tsvideos.core.external.samba

import jcifs.SmbConstants
import org.springframework.stereotype.Component
import java.io.File
import java.security.MessageDigest

/** All operations address one explicit NAS; no fallback to another share. */
@Component
class VerifiedUploadNas(
    private val samba: SambaClient,
    private val directories: NasComponent,
) {
    private fun root(type: SambaClient.NasType) =
        when (type) {
            SambaClient.NasType.VIDEO_STORE_NAS -> samba.videoStoreNas()
            SambaClient.NasType.ORIGINAL_STORE_NAS -> samba.originalStoreNas()
        }

    fun exists(
        type: SambaClient.NasType,
        path: String,
    ): Boolean = root(type).use { root -> root.resolve(path).use { it.exists() } }

    fun size(
        type: SambaClient.NasType,
        path: String,
    ): Long =
        root(type).use { root ->
            root.resolve(path).use {
                check(it.exists()) { "Remote artifact missing" }
                it.length()
            }
        }

    fun sha256(
        type: SambaClient.NasType,
        path: String,
    ): String =
        root(type).use { root ->
            root.resolve(path).use { resource ->
                resource.openInputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
        }

    fun transfer(
        type: SambaClient.NasType,
        path: String,
        source: File,
        onOwned: () -> Unit,
        progress: (Long, Long) -> Unit,
    ) {
        directories.createDirectories(path, type)
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
                        source.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            var transferred = 0L
                            progress(0, source.length())
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                transferred += count
                                progress(transferred, source.length())
                            }
                        }
                    }
            }
        }
    }

    fun finalize(
        type: SambaClient.NasType,
        temporary: String,
        target: String,
    ) {
        root(type).use { root ->
            root.resolve(temporary).use { source ->
                root.resolve(target).use { destination ->
                    check(!destination.exists()) { "Unowned final destination exists" }
                    source.renameTo(destination, false)
                }
            }
        }
    }

    fun delete(
        type: SambaClient.NasType,
        path: String,
    ) {
        root(type).use { root -> root.resolve(path).use { if (it.exists()) it.delete() } }
    }
}
