package me.pinfort.tsvideos.core.external.shell

import me.pinfort.tsvideos.core.exception.ShellCommandTimeoutException
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
        val process =
            ProcessBuilder(command)
                .directory(workingDir)
                .redirectOutput(redirect)
                .redirectError(redirect)
                .start()
        val owned = linkedSetOf<ProcessHandle>()
        val drains = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "shell-stream").apply { isDaemon = true } }
        val stdout = if (capture) drains.submit<String> { process.inputStream.bufferedReader().readText() } else null
        val stderr = if (capture) drains.submit<String> { process.errorStream.bufferedReader().readText() } else null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        try {
            while (true) {
                process.descendants().use { children -> children.forEach { owned.add(it) } }
                if (process.waitFor(25, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() >= deadline) throw TimeoutException()
            }
            // Descendants may retain inherited pipes after the direct process exits.
            val out = stdout?.get(1, TimeUnit.SECONDS).orEmpty()
            val err = stderr?.get(1, TimeUnit.SECONDS).orEmpty()
            return ShellResult(process.exitValue(), out, err)
        } catch (failure: TimeoutException) {
            throw ShellCommandTimeoutException("Command or output drain timed out after ${timeoutSec}s")
        } finally {
            // Always reap owned descendants, including when the parent exited before its pipes closed.
            val interrupted = Thread.interrupted()
            try {
                process.descendants().use { children -> children.forEach { owned.add(it) } }
                terminate(owned.toList().asReversed() + process.toHandle())
            } finally {
                stdout?.cancel(true)
                stderr?.cancel(true)
                drains.shutdownNow()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun terminate(handles: List<ProcessHandle>) {
        handles.filter { it.isAlive }.forEach { it.destroy() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
        while (handles.any { it.isAlive } && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport
                .parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
        handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }
}
