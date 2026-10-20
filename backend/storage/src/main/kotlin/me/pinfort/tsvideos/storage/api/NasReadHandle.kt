package me.pinfort.tsvideos.storage.api

import java.io.InputStream

/**
 * An opened NAS file for reading, independent of the Samba implementation and of Spring `Resource`.
 *
 * The caller owns the handle and closes it once, when it no longer needs any stream: the handle may be opened many
 * times, so closing one stream never closes the handle. [close] releases every stream still open and the underlying
 * file and share root; calling it again does nothing.
 */
interface NasReadHandle : AutoCloseable {
    /** Length read when the handle was opened; later changes to the file are not observed. */
    val length: Long

    /**
     * A new input stream from the start of the file. Closing it releases only that stream.
     * @throws NasStorageException when the NAS cannot open the file; reads and close throw storage exceptions too.
     * @throws IllegalStateException when the handle is already closed.
     */
    fun open(): InputStream

    /**
     * Releases all held streams and resources; idempotent. Everything is released even when one release fails;
     * the first failure is then thrown with the others suppressed.
     */
    override fun close()
}
