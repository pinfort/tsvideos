package me.pinfort.tsvideos.core.component

/** Preserve cancellation through libraries that wrap InterruptedException or only set the flag. */
fun diagnosticCancellation(error: Throwable? = null) {
    if (Thread.currentThread().isInterrupted || generateSequence(error) { it.cause }.take(16).any { it is InterruptedException }) {
        Thread.currentThread().interrupt()
        throw InterruptedException("Diagnostic interrupted")
    }
}
