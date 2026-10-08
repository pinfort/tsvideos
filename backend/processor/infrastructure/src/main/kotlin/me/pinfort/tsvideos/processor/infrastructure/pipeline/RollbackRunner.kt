package me.pinfort.tsvideos.processor.infrastructure.pipeline

/** One instance per processing attempt; includes cleanup of a partially completed stage. */
internal class RollbackRunner {
    private val rollbacks = ArrayDeque<() -> Unit>()

    fun <T> stage(
        rollback: () -> Unit,
        body: () -> T,
    ): T {
        rollbacks.addFirst(rollback)
        try {
            return body()
        } catch (failure: Exception) {
            while (rollbacks.isNotEmpty()) {
                try {
                    rollbacks.removeFirst().invoke()
                } catch (cleanupFailure: Exception) {
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }
}
