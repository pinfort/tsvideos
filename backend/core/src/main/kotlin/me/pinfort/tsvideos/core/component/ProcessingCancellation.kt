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
        owner?.interrupt()
    }

    fun check() {
        if (Thread.interrupted() || stopping) throw InterruptedException("Processing interrupted")
    }

    fun <T> runOwned(operation: () -> T): T {
        synchronized(this) {
            check(owner == null && stopped.count > 0) { "Only one invocation owner is supported" }
            owner = Thread.currentThread()
        }
        try {
            check()
            return operation().also { check() }
        } finally {
            synchronized(this) { owner = null }
            stopped.countDown()
        }
    }

    fun awaitStopped(milliseconds: Long): Boolean = stopped.await(milliseconds, TimeUnit.MILLISECONDS)

    companion object {
        fun isInterrupted(failure: Throwable): Boolean =
            Thread.currentThread().isInterrupted ||
                generateSequence(failure) { it.cause }.take(16).any { it is InterruptedException }

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
