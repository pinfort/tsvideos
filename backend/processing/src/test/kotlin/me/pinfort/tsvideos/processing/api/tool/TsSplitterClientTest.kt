package me.pinfort.tsvideos.processing.api.tool

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.processing.api.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.processing.api.shell.ShellComponent
import java.io.File

class TsSplitterClientTest :
    ExpectSpec({
        val shellComponent = mockk<ShellComponent>()
        val properties =
            ProcessorToolConfigurationProperties(
                tsSplitterPath = "C:\\tools\\TsSplitter.exe",
                amatsukazeAddTaskPath = "C:\\tools\\AmatsukazeAddTask.exe",
                ffprobePath = "ffprobe",
                amatsukaze =
                    ProcessorToolConfigurationProperties.Amatsukaze(
                        host = "localhost",
                        port = 32768,
                        defaultProfile = "30fps_light",
                        atxDivProfile = "30fps_light_atx_div",
                    ),
            )
        val tsSplitterClient = TsSplitterClient(shellComponent, properties)

        context("split") {
            expect("builds the expected argv and returns the exit code") {
                val inFile = File("/recordings/title.m2ts")
                val outDir = File("/recordings/tssplitter")
                every { shellComponent.executeOnWindows(any(), any(), any()) } returns 0

                tsSplitterClient.split(inFile, outDir, 900) shouldBe 0

                verify {
                    shellComponent.executeOnWindows(
                        inFile.parentFile,
                        listOf(
                            "C:\\tools\\TsSplitter.exe",
                            "-SD",
                            "-EIT",
                            "-1SEG",
                            "-OUT",
                            outDir.absolutePath,
                            "-SEP",
                            inFile.absolutePath,
                        ),
                        900,
                    )
                }
            }
        }
    })
