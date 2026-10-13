package me.pinfort.tsvideos.core.component

import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/** One invocation with a producer and any registered workers; admission closes atomically on stop. */
@Component
class ProcessingCancellation {
    @Volatile private var stopping = false
    private val owners = mutableMapOf<Thread, Int>()
    private var completed = false
    private val monitor = Object()

    fun requestStop() {
        synchronized(monitor) {
            if (stopping) return
            stopping = true
            if (owners.isEmpty()) completed = true
            monitor.notifyAll()
            owners.keys.forEach { it.interrupt() }
        }
    }

    fun check() {
        if (Thread.interrupted() || stopping) throw InterruptedException("Processing interrupted")
    }

    fun <T> runOwned(operation: () -> T): T {
        val thread = Thread.currentThread()
        synchronized(monitor) {
            if (stopping) throw InterruptedException("Processing admission stopped")
            check(!completed) { "Invocation already completed" }
            owners[thread] = (owners[thread] ?: 0) + 1
        }
        try {
            check()
            return operation().also { check() }
        } finally {
            synchronized(monitor) {
                val remaining = checkNotNull(owners[thread]) - 1
                if (remaining == 0) owners.remove(thread) else owners[thread] = remaining
                if (owners.isEmpty()) completed = true
                monitor.notifyAll()
            }
        }
    }

    fun awaitStopped(milliseconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds)
        synchronized(monitor) {
            while (!completed) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
            }
            return true
        }
    }

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
