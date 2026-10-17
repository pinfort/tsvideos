package me.pinfort.tsvideos.core.external.shell

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.exception.ShellCommandTimeoutException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

class ShellClientTest :
    ExpectSpec({
        val shellClient = ShellClient()
        val workingDir = File(".")

        expect("interruption tears down the actual owned process tree within a bound") {
            val directory =
                Files
                    .createTempDirectory("shell-tree")
                    .toFile()
            val pidFile = File(directory, "child.pid")
            val failure =
                AtomicReference<Throwable>()
            val worker =
                Thread {
                    try {
                        shellClient.executeCapturingOutput(directory, listOf("bash", "-c", "sleep 60 & echo $! > child.pid; wait"), 60)
                    } catch (caught: Throwable) {
                        failure.set(caught)
                    }
                }
            worker.start()
            try {
                val deadline = System.nanoTime() + 5_000_000_000L
                while ((!pidFile.exists() || pidFile.readText().trim().isEmpty()) && System.nanoTime() < deadline) Thread.sleep(10)
                val pid = pidFile.readText().trim().toLong()
                Thread.sleep(100)
                val start = System.nanoTime()
                worker.interrupt()
                worker.join(4000)
                worker.isAlive shouldBe false
                (System.nanoTime() - start < 4_000_000_000L) shouldBe true
                (failure.get() is InterruptedException) shouldBe true
                // Linux may briefly retain a terminated orphan as a zombie until init reaps it.
                val stat = File("/proc/$pid/stat")
                val running =
                    ProcessHandle.of(pid).map { it.isAlive }.orElse(false) &&
                        (!stat.exists() || !stat.readText().substringAfterLast(") ").startsWith("Z"))
                running shouldBe false
            } finally {
                worker.interrupt()
                worker.join(4000)
                pidFile.delete()
                directory.delete()
            }
        }
        expect("a descendant holding output pipes cannot hang stream draining") {
            val start = System.nanoTime()
            shouldThrow<ShellCommandTimeoutException> {
                shellClient.executeCapturingOutput(workingDir, listOf("bash", "-c", "sleep 60 & sleep 0.2; exit 0"), 10)
            }
            // Bounded by the drain grace, not the 10s command timeout.
            (System.nanoTime() - start < 8_000_000_000L) shouldBe true
        }
        expect("a child detached on purpose survives a successful exit") {
            val result =
                shellClient.executeCapturingOutput(workingDir, listOf("bash", "-c", "sleep 30 >/dev/null 2>&1 & echo $!"), 10)
            val child = ProcessHandle.of(result.stdout.trim().toLong())
            try {
                result.exitCode shouldBe 0
                child.map { it.isAlive }.orElse(false) shouldBe true
            } finally {
                child.ifPresent { it.destroyForcibly() }
            }
        }
        expect("output written by a descendant shortly after the parent exits is still captured") {
            val result =
                shellClient.executeCapturingOutput(workingDir, listOf("bash", "-c", "(sleep 1.5; echo late) & echo early; exit 0"), 10)

            result.exitCode shouldBe 0
            result.stdout shouldBe "early\nlate\n"
        }

        expect("immediate parent exit remains bounded even when ancestry observation races") {
            val directory =
                Files
                    .createTempDirectory("shell-fast-exit")
                    .toFile()
            val pidFile = File(directory, "child.pid")
            try {
                val start = System.nanoTime()
                try {
                    shellClient.executeCapturingOutput(directory, listOf("bash", "-c", "sleep 60 & echo $! > child.pid; exit 0"), 10)
                } catch (_: ShellCommandTimeoutException) {
                }
                (System.nanoTime() - start < 8_000_000_000L) shouldBe true
            } finally {
                // Sampling cannot guarantee observation before reparenting; this test owns the recorded PID.
                if (pidFile.exists()) ProcessHandle.of(pidFile.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
                pidFile.delete()
                directory.delete()
            }
        }
        context("execute") {
            expect("success with an argument containing spaces") {
                val exitCode =
                    shellClient.execute(
                        workingDir,
                        listOf("bash", "-c", "test \"\$1\" = \"a value with spaces\"", "--", "a value with spaces"),
                        10,
                    )

                exitCode shouldBe 0
            }

            expect("returns the process exit code") {
                val exitCode = shellClient.execute(workingDir, listOf("bash", "-c", "exit 3"), 10)

                exitCode shouldBe 3
            }

            expect("timeout kills the process and throws") {
                shouldThrow<ShellCommandTimeoutException> {
                    shellClient.execute(workingDir, listOf("sleep", "5"), 1)
                }
            }
        }

        context("executeCapturingOutput") {
            expect("captures stdout and stderr separately") {
                val result =
                    shellClient.executeCapturingOutput(
                        workingDir,
                        listOf("bash", "-c", "echo out; echo err 1>&2"),
                        10,
                    )

                result.exitCode shouldBe 0
                result.stdout shouldBe "out\n"
                result.stderr shouldBe "err\n"
            }

            expect("does not deadlock on large output") {
                val result =
                    shellClient.executeCapturingOutput(
                        workingDir,
                        listOf("bash", "-c", "head -c 1000000 /dev/zero | tr '\\0' 'a'"),
                        10,
                    )

                result.exitCode shouldBe 0
                result.stdout.length shouldBe 1000000
            }

            expect("timeout kills the process and throws") {
                shouldThrow<ShellCommandTimeoutException> {
                    shellClient.executeCapturingOutput(workingDir, listOf("sleep", "5"), 1)
                }
            }
        }
    })
