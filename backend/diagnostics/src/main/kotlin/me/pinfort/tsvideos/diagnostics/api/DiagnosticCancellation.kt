package me.pinfort.tsvideos.diagnostics.api

/** Preserve cancellation through libraries that wrap InterruptedException or only set the flag. */
fun diagnosticCancellation(error: Throwable? = null) {
    if (Thread.currentThread().isInterrupted || generateSequence(error) { it.cause }.take(16).any { it is InterruptedException }) {
        Thread.currentThread().interrupt()
        if (error is InterruptedException) throw error
        throw InterruptedException("Diagnostic interrupted").apply { if (error != null) initCause(error) }
    }
}
