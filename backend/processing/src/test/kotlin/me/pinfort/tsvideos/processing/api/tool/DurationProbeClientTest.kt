package me.pinfort.tsvideos.processing.api.tool

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.processing.api.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.processing.api.shell.ShellClient
import me.pinfort.tsvideos.processing.api.shell.ShellResult
import org.slf4j.Logger
import java.io.File

class DurationProbeClientTest :
    ExpectSpec({
        val shellClient = mockk<ShellClient>()
        val logger = mockk<Logger>(relaxed = true)
        val properties =
            ProcessorToolConfigurationProperties(
                tsSplitterPath = "tsSplitter",
                amatsukazeAddTaskPath = "amatsukaze",
                ffprobePath = "ffprobe",
                amatsukaze =
                    ProcessorToolConfigurationProperties.Amatsukaze(
                        host = "localhost",
                        port = 32768,
                        defaultProfile = "30fps_light",
                        atxDivProfile = "30fps_light_atx_div",
                    ),
            )
        val durationProbeClient = DurationProbeClient(shellClient, properties, logger)
        val file = File("/recordings/title.m2ts")

        context("probe") {
            expect("parses stdout on success") {
                every { shellClient.executeCapturingOutput(any(), any(), any()) } returns ShellResult(0, "12.345000\n", "")

                durationProbeClient.probe(file) shouldBe 12.345

                verify {
                    shellClient.executeCapturingOutput(
                        file.parentFile,
                        listOf(
                            "ffprobe",
                            "-v",
                            "error",
                            "-show_entries",
                            "format=duration",
                            "-of",
                            "default=noprint_wrappers=1:nokey=1",
                            file.absolutePath,
                        ),
                        60,
                    )
                }
            }

            expect("does not swallow interruption as unknown duration") {
                every { shellClient.executeCapturingOutput(any(), any(), any()) } throws InterruptedException()
                io.kotest.assertions.throwables
                    .shouldThrow<InterruptedException> { durationProbeClient.probe(file) }
            }
            expect("returns 0.0 on non-zero exit code") {
                every { shellClient.executeCapturingOutput(any(), any(), any()) } returns ShellResult(1, "", "error")

                durationProbeClient.probe(file) shouldBe 0.0
            }

            expect("returns 0.0 on unparseable stdout") {
                every { shellClient.executeCapturingOutput(any(), any(), any()) } returns ShellResult(0, "not-a-number", "")

                durationProbeClient.probe(file) shouldBe 0.0
            }

            expect("returns 0.0 when the shell call throws, e.g. on timeout") {
                every { shellClient.executeCapturingOutput(any(), any(), any()) } throws RuntimeException("timed out")

                durationProbeClient.probe(file) shouldBe 0.0
            }
        }
    })
