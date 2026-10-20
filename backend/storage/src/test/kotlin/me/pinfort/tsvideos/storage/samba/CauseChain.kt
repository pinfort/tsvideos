package me.pinfort.tsvideos.storage.samba

/**
 * The exception and its causes. Callers classify storage failures by walking this chain (network timeouts are
 * retryable, interruption cancels), so the translation must keep those causes reachable.
 */
internal fun Throwable.causeChain(): List<Throwable> = generateSequence(this) { it.cause }.take(16).toList()
