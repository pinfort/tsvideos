package me.pinfort.tsvideos.processor.console

import me.pinfort.tsvideos.processing.api.ProcessingCancellation
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** The entire JVM hook, including Spring destruction, has one shared deadline. */
class ProcessorShutdown(
    private val cancellation: ProcessingCancellation,
    private val graceSeconds: Long,
    private val logger: Logger = LoggerFactory.getLogger(ProcessorShutdown::class.java),
    private val close: () -> Unit,
) {
    init {
        require(graceSeconds in 1..300) { "processor.shutdown.grace-seconds must be between 1 and 300" }
    }

    fun stop() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(graceSeconds)
        cancellation.requestStop()
        // Keep the last fifth of the grace for closing the context, so even a hung owner leaves time for a clean DB close.
        val waitMillis = graceSeconds * 1000 * (100 - CLOSE_SHARE_PERCENT) / 100
        if (!cancellation.awaitStopped(waitMillis)) {
            logger.warn("Processing did not stop within ${waitMillis}ms; closing anyway, inspect ACTIVE attempts after exit")
        }
        val closer = Thread(close, "processor-context-close").apply { isDaemon = true }
        closer.start()
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remaining > 0) closer.join(remaining)
        if (closer.isAlive) logger.warn("Context close did not finish within the ${graceSeconds}s grace; the JVM exits mid-close")
    }

    private companion object {
        const val CLOSE_SHARE_PERCENT = 20
    }
}
