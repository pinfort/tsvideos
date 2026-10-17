package me.pinfort.tsvideos.core.external.shell

import me.pinfort.tsvideos.core.exception.ShellCommandTimeoutException
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport

@Component
class ShellClient {
    fun execute(
        workingDir: File,
        command: List<String>,
        timeoutSec: Long = 600,
    ): Int = run(workingDir, command, timeoutSec, false).exitCode

    fun executeCapturingOutput(
        workingDir: File,
        command: List<String>,
        timeoutSec: Long = 600,
    ): ShellResult = run(workingDir, command, timeoutSec, true)

    private fun run(
        workingDir: File,
        command: List<String>,
        timeoutSec: Long,
        capture: Boolean,
    ): ShellResult {
        require(timeoutSec > 0)
        if (Thread.interrupted()) throw InterruptedException("Command interrupted before start")
        val redirect = if (capture) ProcessBuilder.Redirect.PIPE else ProcessBuilder.Redirect.INHERIT
        // Created before the process so nothing between start() and the try below can leak a running process.
        val drains = if (capture) Executors.newFixedThreadPool(2, DRAIN_THREADS) else null
        val process =
            try {
                ProcessBuilder(command)
                    .directory(workingDir)
                    .redirectOutput(redirect)
                    .redirectError(redirect)
                    .start()
            } catch (failure: Exception) {
                drains?.shutdownNow()
                throw failure
            }
        val owned = linkedSetOf<ProcessHandle>()
        var stdout: Future<String>? = null
        var stderr: Future<String>? = null
        var exitedNormally = false
        val started = System.nanoTime()
        val deadline = started + TimeUnit.SECONDS.toNanos(timeoutSec)
        try {
            stdout = drains?.submit<String> { process.inputStream.bufferedReader().readText() }
            stderr = drains?.submit<String> { process.errorStream.bufferedReader().readText() }
            while (true) {
                process.descendants().use { children -> children.forEach { owned.add(it) } }
                // waitFor returns as soon as the process exits; the interval only bounds descendant sampling,
                // which is dense early to catch short-lived children and coarse afterwards to spare /proc scans.
                val poll = if (System.nanoTime() - started < EARLY_SAMPLING_NANOS) EARLY_POLL_MILLIS else LATE_POLL_MILLIS
                if (process.waitFor(poll, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() >= deadline) {
                    throw ShellCommandTimeoutException("Command timed out after ${timeoutSec}s, command=$command")
                }
            }
            // The streams were drained concurrently, so only a descendant still holding the inherited pipes
            // keeps them open now. Give such writers a grace period, but never past the command's own deadline.
            val drainDeadline = minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_GRACE_SECONDS))
            val out = stdout?.let { drain(it, drainDeadline, command) }.orEmpty()
            val err = stderr?.let { drain(it, drainDeadline, command) }.orEmpty()
            exitedNormally = true
            return ShellResult(process.exitValue(), out, err)
        } finally {
            // Reap owned descendants only when the command did not finish on its own (timeout, interruption or a
            // pipe-holding descendant). After a normal exit a surviving child was detached on purpose (launchers,
            // daemonizing clients) and is left alone, as before cooperative cancellation.
            var interrupted = Thread.interrupted()
            try {
                if (!exitedNormally) {
                    process.descendants().use { children -> children.forEach { owned.add(it) } }
                    if (terminate(owned.toList().asReversed() + process.toHandle())) interrupted = true
                }
            } finally {
                stdout?.cancel(true)
                stderr?.cancel(true)
                drains?.shutdownNow()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun drain(
        stream: Future<String>,
        deadline: Long,
        command: List<String>,
    ): String =
        try {
            stream.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
        } catch (failure: TimeoutException) {
            throw ShellCommandTimeoutException(
                "Command exited but its output was still open after the ${DRAIN_GRACE_SECONDS}s drain grace; " +
                    "a descendant may hold the pipes, command=$command",
            )
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }

    /**
     * @return whether another interrupt (e.g. a repeated stop request) arrived meanwhile. It is absorbed so that
     * parking keeps waiting instead of spinning, which keeps each graceful window intact, and reported for restoring.
     */
    private fun terminate(handles: List<ProcessHandle>): Boolean {
        val children = handles.dropLast(1)
        val parent = handles.last()
        var interrupted = false

        fun waitBriefly(
            targets: List<ProcessHandle>,
            millis: Long,
        ) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
            while (targets.any { it.isAlive } && System.nanoTime() < deadline) {
                if (Thread.interrupted()) interrupted = true
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
            }
        }
        children.filter { it.isAlive }.forEach { it.destroy() }
        // SIGTERM first: tools such as ffmpeg need a moment to flush before SIGKILL. Failure paths only.
        waitBriefly(children, CHILD_TERMINATE_GRACE_MILLIS)
        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
        // Give the direct parent a chance to reap its children before terminating it.
        waitBriefly(handles, PARENT_REAP_GRACE_MILLIS)
        if (parent.isAlive) parent.destroy()
        waitBriefly(listOf(parent), PARENT_TERMINATE_GRACE_MILLIS)
        if (parent.isAlive) parent.destroyForcibly()
        return interrupted
    }

    private companion object {
        const val EARLY_SAMPLING_NANOS = 1_000_000_000L
        const val EARLY_POLL_MILLIS = 25L
        const val LATE_POLL_MILLIS = 250L
        const val DRAIN_GRACE_SECONDS = 5L
        const val CHILD_TERMINATE_GRACE_MILLIS = 1000L
        const val PARENT_REAP_GRACE_MILLIS = 200L
        const val PARENT_TERMINATE_GRACE_MILLIS = 500L
        val DRAIN_THREADS = ThreadFactory { runnable -> Thread(runnable, "shell-stream").apply { isDaemon = true } }
    }
}
