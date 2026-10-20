package me.pinfort.tsvideos.manager.api.stream

import me.pinfort.tsvideos.storage.api.NasAccessException
import me.pinfort.tsvideos.storage.api.NasReadHandle
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * An in-memory [NasReadHandle] that records its lifetime: how often it was opened and closed, and whether every
 * stream it handed out was released. Opening a closed handle fails, like the Samba handle, so a handle closed before
 * the body was written makes the response fail instead of passing silently.
 */
class FakeReadHandle(
    private val content: ByteArray,
    private val openFailure: IOException? = null,
) : NasReadHandle {
    var opens = 0
        private set
    var closes = 0
        private set
    private val streams = mutableListOf<TrackedStream>()

    val openStreams: Int get() = synchronized(this) { streams.count { !it.closed } }

    override val length: Long get() = content.size.toLong()

    @Synchronized
    override fun open(): InputStream {
        check(closes == 0) { "NAS read handle is closed" }
        opens++
        openFailure?.let { throw it }
        return TrackedStream(content).also { streams += it }
    }

    @Synchronized
    override fun close() {
        closes++
        streams.forEach { it.close() }
    }

    class TrackedStream(
        content: ByteArray,
    ) : ByteArrayInputStream(content) {
        var closed = false
            private set

        override fun close() {
            closed = true
        }
    }

    companion object {
        fun failingOpen(content: ByteArray) = FakeReadHandle(content, NasAccessException("NAS read failed", IOException("gone")))
    }
}
