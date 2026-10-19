package me.pinfort.tsvideos.core.external.samba

import jcifs.SmbConstants
import jcifs.SmbResource
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import org.springframework.stereotype.Component
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/**
 * Short-timeout NAS connection for doctor/preflight, usable without Spring.
 * Exposes only the operations the diagnostics need; jcifs types stay internal, and jcifs failures of every
 * operation, handle and stream surface as storage exceptions ([SambaFailures]).
 */
@Component
class DiagnosticNasClient {
    interface Connection : AutoCloseable {
        /** The share root when [baseDir] is blank, otherwise the base directory under it. */
        fun directory(baseDir: String): Directory
    }

    interface Directory : AutoCloseable {
        /** True when the path exists and is a directory. */
        fun isDirectory(): Boolean

        /** Opens the listing and fetches at most one entry, without reading any file. */
        fun listFirstEntry()

        fun file(name: String): FileHandle
    }

    interface FileHandle : AutoCloseable {
        /** Creates the file exclusively; fails if it already exists. */
        fun createExclusive(): OutputStream

        fun openInputStream(): InputStream

        fun delete()
    }

    fun open(
        url: String,
        username: String,
        password: String,
    ): Connection =
        sambaOperation("connect") {
            val properties =
                Properties().apply {
                    setProperty("jcifs.smb.client.minVersion", "SMB202")
                    setProperty("jcifs.smb.client.maxVersion", "SMB311")
                    setProperty("jcifs.smb.client.connTimeout", "3000")
                    setProperty("jcifs.smb.client.responseTimeout", "3000")
                    setProperty("jcifs.smb.client.soTimeout", "3000")
                    setProperty("jcifs.smb.client.maxRequestRetries", "1")
                }
            val base = BaseContext(PropertyConfiguration(properties))
            val authenticated = base.withCredentials(NtlmPasswordAuthenticator(username, password))
            try {
                val root = SmbFile(if (url.endsWith('/')) url else "$url/", authenticated)
                connection(root) {
                    authenticated.close()
                    base.close()
                }
            } catch (failure: Exception) {
                authenticated.close()
                base.close()
                throw failure
            }
        }

    internal companion object {
        /** Wraps an opened share root; [closeContext] runs after the root is closed. */
        internal fun connection(
            root: SmbResource,
            closeContext: () -> Unit,
        ): Connection = SmbConnection(root, closeContext)
    }

    private class SmbConnection(
        private val root: SmbResource,
        private val closeContext: () -> Unit,
    ) : Connection {
        // The root is closed with the connection, so its directory view must not close it.
        override fun directory(baseDir: String): Directory =
            sambaOperation("directory lookup") {
                if (baseDir.isBlank()) {
                    SmbDirectory(root, owned = false)
                } else {
                    SmbDirectory(root.resolve("${baseDir.trim('/')}/"), owned = true)
                }
            }

        override fun close() {
            sambaOperation("disconnect") {
                try {
                    root.close()
                } finally {
                    closeContext()
                }
            }
        }
    }

    private class SmbDirectory(
        private val resource: SmbResource,
        private val owned: Boolean,
    ) : Directory {
        override fun isDirectory(): Boolean = sambaOperation("directory check") { resource.exists() && resource.isDirectory }

        // Iteration reports failures as RuntimeCIFSException, which is translated too.
        override fun listFirstEntry() {
            sambaOperation("directory listing") {
                resource.children().use { children ->
                    if (children.hasNext()) children.next().close()
                }
            }
        }

        override fun file(name: String): FileHandle = sambaOperation("file lookup") { SmbFileHandle(resource.resolve(name)) }

        override fun close() {
            if (owned) sambaOperation("close") { resource.close() }
        }
    }

    private class SmbFileHandle(
        private val resource: SmbResource,
    ) : FileHandle {
        // jcifs createNewFile uses OPEN_IF on SMB2. Explicit O_EXCL is required for ownership.
        override fun createExclusive(): OutputStream =
            SambaOutputStream(
                sambaOperation("exclusive create") {
                    resource.openOutputStream(
                        true,
                        SmbConstants.O_WRONLY or SmbConstants.O_CREAT or SmbConstants.O_EXCL,
                        SmbConstants.FILE_WRITE_DATA,
                        SmbConstants.FILE_NO_SHARE,
                    )
                },
                "write",
            )

        override fun openInputStream(): InputStream =
            SambaInputStream(sambaOperation("open for reading") { resource.openInputStream() }, "read")

        override fun delete() = sambaOperation("delete") { resource.delete() }

        override fun close() = sambaOperation("close") { resource.close() }
    }
}
