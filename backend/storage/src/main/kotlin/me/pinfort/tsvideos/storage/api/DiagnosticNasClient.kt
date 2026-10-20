package me.pinfort.tsvideos.storage.api

import me.pinfort.tsvideos.storage.samba.SambaDiagnosticNasClient
import java.io.InputStream
import java.io.OutputStream

/**
 * Short-timeout NAS connection for doctor/preflight, usable without Spring ([create]).
 * Exposes only the operations the diagnostics need; failures of every operation, handle and stream surface as
 * [NasStorageException].
 */
interface DiagnosticNasClient {
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

    /** Connects with an independent short-timeout context, closed with the returned connection. */
    fun open(
        url: String,
        username: String,
        password: String,
    ): Connection

    companion object {
        /** The Samba implementation, built without a Spring context or any configuration. */
        fun create(): DiagnosticNasClient = SambaDiagnosticNasClient()
    }
}
