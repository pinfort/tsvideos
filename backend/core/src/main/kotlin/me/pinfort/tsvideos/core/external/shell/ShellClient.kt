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
        val process =
            ProcessBuilder(command)
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
        val owned = linkedSetOf<ProcessHandle>()
        val drains = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "shell-stream").apply { isDaemon = true } }
        val stdout =
            drains.submit<String> {
                if (capture) {
                    process.inputStream.bufferedReader().readText()
                } else {
                    process.inputStream.copyTo(System.err)
                    ""
                }
            }
        val stderr =
            drains.submit<String> {
                if (capture) {
                    process.errorStream.bufferedReader().readText()
                } else {
                    process.errorStream.copyTo(System.err)
                    ""
                }
            }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        try {
            while (true) {
                process.descendants().use { children -> children.forEach { owned.add(it) } }
                if (process.waitFor(25, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() >= deadline) throw TimeoutException()
            }
            // Descendants may retain inherited pipes after the direct process exits.
            val out = stdout.get(1, TimeUnit.SECONDS)
            val err = stderr.get(1, TimeUnit.SECONDS)
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
                stdout.cancel(true)
                stderr.cancel(true)
                drains.shutdownNow()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun terminate(handles: List<ProcessHandle>) {
        val children = handles.dropLast(1)
        val parent = handles.last()

        fun waitBriefly(
            targets: List<ProcessHandle>,
            millis: Long,
        ) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
            while (targets.any { it.isAlive } && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport
                    .parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
            }
        }
        children.filter { it.isAlive }.forEach { it.destroy() }
        waitBriefly(children, 200)
        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
        // Give the direct parent a chance to reap its children before terminating it.
        waitBriefly(handles, 200)
        if (parent.isAlive) parent.destroy()
        waitBriefly(listOf(parent), 100)
        if (parent.isAlive) parent.destroyForcibly()
    }
}
