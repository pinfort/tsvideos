package me.pinfort.tsvideos.storage.samba

import jcifs.SmbResource
import me.pinfort.tsvideos.storage.api.NasReadHandle
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Built only by [SambaNasComponent.openForRead]. Owns the share [root] and the resolved [resource]; every stream it opened
 * and has not seen closed is closed with them. Opening, reading and closing throw storage exceptions, not jcifs ones.
 */
internal class SambaReadHandle(
    private val root: SmbResource,
    private val resource: SmbResource,
    override val length: Long,
) : NasReadHandle {
    private val streams = mutableSetOf<TrackedStream>()
    private var closed = false

    override fun open(): InputStream {
        synchronized(this) { check(!closed) { "NAS read handle is closed" } }
        // Opened outside the lock so a concurrent close (client disconnect) does not wait on the network.
        val stream = TrackedStream(SambaInputStream(sambaOperation("read") { resource.openInputStream() }, "read"))
        val adopted = synchronized(this) { !closed && streams.add(stream) }
        if (!adopted) {
            val closedFailure = IllegalStateException("NAS read handle is closed")
            try {
                stream.close()
            } catch (e: Exception) {
                closedFailure.addSuppressed(e)
            }
            throw closedFailure
        }
        return stream.buffered()
    }

    /** Closes every remaining stream, then the file and the root, even if one fails; the first failure is thrown. */
    override fun close() {
        val remaining =
            synchronized(this) {
                if (closed) return
                closed = true
                streams.toList().also { streams.clear() }
            }
        val closeables: List<AutoCloseable> =
            remaining +
                listOf(
                    AutoCloseable { sambaOperation("close") { resource.close() } },
                    AutoCloseable { sambaOperation("close") { root.close() } },
                )
        var failure: Exception? = null
        closeables.forEach { closeable ->
            try {
                closeable.close()
            } catch (e: Exception) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }

    private fun release(stream: TrackedStream) {
        synchronized(this) { streams.remove(stream) }
    }

    /** Closing it twice (by its reader and by the handle) closes the SMB stream once. */
    private inner class TrackedStream(
        delegate: InputStream,
    ) : FilterInputStream(delegate) {
        private val streamClosed = AtomicBoolean(false)

        override fun close() {
            if (!streamClosed.compareAndSet(false, true)) return
            try {
                super.close()
            } finally {
                release(this)
            }
        }
    }
}
