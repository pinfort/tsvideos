package me.pinfort.tsvideos.processor.console

import me.pinfort.tsvideos.core.component.ProcessingCancellation
import java.util.concurrent.TimeUnit

/** The entire JVM hook, including Spring destruction, has one shared deadline. */
class ProcessorShutdown(
    private val cancellation: ProcessingCancellation,
    private val graceSeconds: Long,
    private val close: () -> Unit,
) {
    init {
        require(graceSeconds in 1..300) { "processor.shutdown.grace-seconds must be between 1 and 300" }
    }

    fun stop() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(graceSeconds)
        cancellation.requestStop()
        cancellation.awaitStopped(graceSeconds * 1000)
        val closer = Thread(close, "processor-context-close").apply { isDaemon = true }
        closer.start()
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remaining > 0) closer.join(remaining)
    }
}
