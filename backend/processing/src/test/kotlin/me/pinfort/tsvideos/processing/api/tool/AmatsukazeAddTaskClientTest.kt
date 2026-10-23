package me.pinfort.tsvideos.processing.api.tool

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.processing.api.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.processing.api.shell.ShellComponent
import java.io.File

class AmatsukazeAddTaskClientTest :
    ExpectSpec({
        val shellComponent = mockk<ShellComponent>()
        val properties =
            ProcessorToolConfigurationProperties(
                tsSplitterPath = "C:\\tools\\TsSplitter.exe",
                amatsukazeAddTaskPath = "C:\\tools\\AmatsukazeAddTask.exe",
                ffprobePath = "ffprobe",
                amatsukaze =
                    ProcessorToolConfigurationProperties.Amatsukaze(
                        host = "amatsukaze-host",
                        port = 32768,
                        defaultProfile = "30fps_light",
                        atxDivProfile = "30fps_light_atx_div",
                    ),
            )
        val amatsukazeAddTaskClient = AmatsukazeAddTaskClient(shellComponent, properties)

        context("addTask") {
            expect("builds the expected argv and returns the exit code, ignored by the caller") {
                val file = File("/recordings/tssplitter/title.m2ts")
                val outDir = File("/recordings/tssplitter/encoded")
                every { shellComponent.executeOnWindows(any(), any(), any()) } returns 1

                amatsukazeAddTaskClient.addTask(file, outDir, "30fps_light") shouldBe 1

                verify {
                    shellComponent.executeOnWindows(
                        file.parentFile,
                        listOf(
                            "C:\\tools\\AmatsukazeAddTask.exe",
                            "-f",
                            file.absolutePath,
                            "-ip",
                            "amatsukaze-host",
                            "-p",
                            "32768",
                            "-o",
                            outDir.absolutePath,
                            "-s",
                            "30fps_light",
                            "--priority",
                            "3",
                            "--no-move",
                        ),
                        600,
                    )
                }
            }
        }
    })
