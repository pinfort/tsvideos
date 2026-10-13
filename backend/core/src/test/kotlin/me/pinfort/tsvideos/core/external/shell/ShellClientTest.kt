package me.pinfort.tsvideos.core.external.shell

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.exception.ShellCommandTimeoutException
import java.io.File

class ShellClientTest :
    ExpectSpec({
        val shellClient = ShellClient()
        val workingDir = File(".")
        expect("uncaptured child stdout and stderr are diagnostics on parent stderr") {
            val originalOut = System.out
            val originalErr = System.err
            val out = java.io.ByteArrayOutputStream()
            val err = java.io.ByteArrayOutputStream()
            try {
                System.setOut(java.io.PrintStream(out))
                System.setErr(java.io.PrintStream(err))
                shellClient.execute(workingDir, listOf("bash", "-c", "echo child-out-marker; echo child-err-marker >&2")) shouldBe 0
                out.toString().contains("child-out-marker") shouldBe false
                err.toString().contains("child-out-marker") shouldBe true
                err.toString().contains("child-err-marker") shouldBe true
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }
        }

        expect("interruption tears down the actual owned process tree within a bound") {
            val directory =
                java.nio.file.Files
                    .createTempDirectory("shell-tree")
                    .toFile()
            val pidFile = File(directory, "child.pid")
            val failure =
                java.util.concurrent.atomic
                    .AtomicReference<Throwable>()
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
        listOf(0, 7).forEach { code ->
            expect("uncaptured parent exit $code survives an observed descendant retaining pipes") {
                val directory =
                    java.nio.file.Files
                        .createTempDirectory("shell-drain")
                        .toFile()
                val pidFile = File(directory, "child.pid")
                try {
                    val start = System.nanoTime()
                    shellClient.execute(
                        directory,
                        listOf("bash", "-c", "sleep 60 & echo $! > child.pid; sleep 0.2; exit $code"),
                        10,
                    ) shouldBe
                        code
                    (System.nanoTime() - start < 4_000_000_000L) shouldBe true
                    val pid = pidFile.readText().trim().toLong()
                    val stat = File("/proc/$pid/stat")
                    val running =
                        ProcessHandle.of(pid).map { it.isAlive }.orElse(false) &&
                            (!stat.exists() || !stat.readText().substringAfterLast(") ").startsWith("Z"))
                    running shouldBe false
                } finally {
                    if (pidFile.exists()) ProcessHandle.of(pidFile.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
                    pidFile.delete()
                    directory.delete()
                }
            }
        }
        expect("a descendant holding output pipes cannot hang stream draining") {
            val start = System.nanoTime()
            shouldThrow<ShellCommandTimeoutException> {
                shellClient.executeCapturingOutput(workingDir, listOf("bash", "-c", "sleep 60 & sleep 0.2; exit 0"), 10)
            }
            (System.nanoTime() - start < 4_000_000_000L) shouldBe true
        }

        expect("immediate parent exit remains bounded even when ancestry observation races") {
            val directory =
                java.nio.file.Files
                    .createTempDirectory("shell-fast-exit")
                    .toFile()
            val pidFile = File(directory, "child.pid")
            try {
                val start = System.nanoTime()
                try {
                    shellClient.executeCapturingOutput(directory, listOf("bash", "-c", "sleep 60 & echo $! > child.pid; exit 0"), 10)
                } catch (_: ShellCommandTimeoutException) {
                }
                (System.nanoTime() - start < 4_000_000_000L) shouldBe true
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
