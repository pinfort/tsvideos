package me.pinfort.tsvideos.processing.api.shell

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.processing.api.RunningPlatformComponent
import me.pinfort.tsvideos.processing.domain.InvalidEnvironmentException
import java.io.File

class ShellComponentTest :
    ExpectSpec({
        val runningPlatformComponent = mockk<RunningPlatformComponent>()
        val shellClient = mockk<ShellClient>()
        val shellComponent = ShellComponent(runningPlatformComponent, shellClient)

        context("executeOnWindows") {
            expect("success") {
                every { runningPlatformComponent.runningOnWindows() } returns true
                every { shellClient.execute(any(), any(), any()) } returns 1

                shellComponent.executeOnWindows(File("."), listOf("ls"), 10) shouldBe 1
            }

            expect("failed") {
                every { runningPlatformComponent.runningOnWindows() } returns false

                val exception =
                    shouldThrow<InvalidEnvironmentException> {
                        shellComponent.executeOnWindows(File("."), listOf("ls"), 10)
                    }
                exception.message shouldBe "This function must be called on Windows only."
            }
        }

        context("executeOnWindowsCapturingOutput") {
            expect("success") {
                every { runningPlatformComponent.runningOnWindows() } returns true
                every { shellClient.executeCapturingOutput(any(), any(), any()) } returns
                    ShellResult(0, "out", "err")

                shellComponent.executeOnWindowsCapturingOutput(File("."), listOf("ls"), 10) shouldBe
                    ShellResult(0, "out", "err")
            }

            expect("failed") {
                every { runningPlatformComponent.runningOnWindows() } returns false

                val exception =
                    shouldThrow<InvalidEnvironmentException> {
                        shellComponent.executeOnWindowsCapturingOutput(File("."), listOf("ls"), 10)
                    }
                exception.message shouldBe "This function must be called on Windows only."
            }
        }
    })
