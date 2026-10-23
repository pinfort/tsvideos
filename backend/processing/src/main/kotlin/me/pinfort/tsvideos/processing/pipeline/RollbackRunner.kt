package me.pinfort.tsvideos.processing.pipeline

import me.pinfort.tsvideos.processing.api.ProcessingCancellation

/** One instance per processing attempt; includes cleanup of a partially completed stage. */
internal class RollbackRunner {
    private val rollbacks = ArrayDeque<() -> Unit>()

    /** False once any rollback has thrown, i.e. earlier stages may have left state behind. */
    var rolledBackCleanly = true
        private set

    fun <T> stage(
        rollback: () -> Unit,
        body: () -> T,
    ): T {
        rollbacks.addFirst(rollback)
        try {
            return body()
        } catch (failure: Exception) {
            // Split filename prefixes do not prove ownership. Preserve evidence on interruption.
            if (ProcessingCancellation.isInterrupted(failure)) throw failure
            while (rollbacks.isNotEmpty()) {
                try {
                    rollbacks.removeFirst().invoke()
                } catch (cleanupFailure: Exception) {
                    rolledBackCleanly = false
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }
}
