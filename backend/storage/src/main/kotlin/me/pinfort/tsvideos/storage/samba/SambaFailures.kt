package me.pinfort.tsvideos.storage.samba

import jcifs.CIFSException
import jcifs.RuntimeCIFSException
import jcifs.smb.SmbAuthException
import me.pinfort.tsvideos.storage.api.NasAccessException
import me.pinfort.tsvideos.storage.api.NasAuthenticationException
import me.pinfort.tsvideos.storage.api.NasStorageException
import java.io.InputStream
import java.io.OutputStream

/**
 * Translates jcifs failures into storage exceptions at the Samba boundary, so public NAS operations never throw a
 * jcifs type. Only jcifs exceptions are translated: validation, interruption and callback failures propagate
 * unchanged. The jcifs exception becomes the cause, keeping its own cause chain and suppressed close failures.
 */
internal object SambaFailures {
    private const val MAX_CAUSE_DEPTH = 16

    fun translate(
        operation: String,
        failure: Exception,
    ): NasStorageException =
        // jcifs messages can contain the server URL, so they are kept only in the cause.
        if (generateSequence<Throwable>(failure) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is SmbAuthException }) {
            NasAuthenticationException("NAS $operation failed: credentials rejected", failure)
        } else {
            NasAccessException("NAS $operation failed", failure)
        }
}

/** Runs [block], translating jcifs failures thrown by it; inline so the block may return from its caller. */
internal inline fun <T> sambaOperation(
    operation: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: CIFSException) {
        throw SambaFailures.translate(operation, failure)
    } catch (failure: RuntimeCIFSException) {
        throw SambaFailures.translate(operation, failure)
    }

/** An SMB input stream whose read/close failures are storage exceptions. */
internal class SambaInputStream(
    private val delegate: InputStream,
    private val operation: String,
) : InputStream() {
    override fun read(): Int = sambaOperation(operation) { delegate.read() }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = sambaOperation(operation) { delegate.read(b, off, len) }

    override fun skip(n: Long): Long = sambaOperation(operation) { delegate.skip(n) }

    override fun available(): Int = sambaOperation(operation) { delegate.available() }

    override fun close() = sambaOperation(operation) { delegate.close() }
}

/** An SMB output stream whose write/flush/close failures are storage exceptions. */
internal class SambaOutputStream(
    private val delegate: OutputStream,
    private val operation: String,
) : OutputStream() {
    override fun write(b: Int) = sambaOperation(operation) { delegate.write(b) }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) = sambaOperation(operation) { delegate.write(b, off, len) }

    override fun flush() = sambaOperation(operation) { delegate.flush() }

    override fun close() = sambaOperation(operation) { delegate.close() }
}
