package me.pinfort.tsvideos.core.component

import org.springframework.stereotype.Component
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One CLI invocation, one owner. This is not a worker scheduler. */
@Component
class ProcessingCancellation {
    @Volatile private var stopping = false
    private var owner: Thread? = null
    private val stopped = CountDownLatch(1)

    @Synchronized
    fun requestStop() {
        stopping = true
        // With no owner there is nothing to wait for: a later runOwned refuses to start, and a shutdown hook that
        // fires before (or without) runOwned must not sit out the whole grace period.
        owner?.interrupt() ?: stopped.countDown()
    }

    /**
     * Throws once a stop is requested. Like any InterruptedException thrower it consumes the interrupt flag; the
     * thrown exception carries the stop instead, and [isInterrupted] recognises it through the cause chain.
     */
    fun check() {
        if (Thread.interrupted() || stopping) throw InterruptedException("Processing interrupted")
    }

    fun <T> runOwned(operation: () -> T): T {
        synchronized(this) {
            if (stopping) throw InterruptedException("Processing interrupted")
            check(owner == null && stopped.count > 0) { "Only one invocation owner is supported" }
            owner = Thread.currentThread()
        }
        try {
            check()
            // No trailing check: an operation that returned has committed its work, so a stop arriving just after
            // the last unit must not report a completed run as interrupted. Work stops at its own checks instead.
            return operation()
        } finally {
            synchronized(this) { owner = null }
            stopped.countDown()
        }
    }

    fun awaitStopped(milliseconds: Long): Boolean = stopped.await(milliseconds, TimeUnit.MILLISECONDS)

    companion object {
        private const val MAX_CAUSE_DEPTH = 16

        /**
         * True when the failure stems from an interruption, or when the current thread is interrupted at all. The flag
         * check catches interruptions surfacing without an InterruptedException in the chain (NIO channels, JDBC
         * drivers). It is not a stray signal: in this one-owner CLI only [requestStop] interrupts the owner, so a set
         * flag means a stop was requested, and callers deliberately preserve evidence (skip rollback, rethrow without
         * notifying) rather than clean up. The cause chain is capped at [MAX_CAUSE_DEPTH] links to stay bounded on cyclic chains.
         */
        fun isInterrupted(failure: Throwable): Boolean =
            Thread.currentThread().isInterrupted ||
                generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is InterruptedException }

        /** Permit short recovery transactions to obtain DB connections, then preserve interruption. */
        fun <T> recovery(operation: () -> T): T {
            val interrupted = Thread.interrupted()
            try {
                return operation()
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
