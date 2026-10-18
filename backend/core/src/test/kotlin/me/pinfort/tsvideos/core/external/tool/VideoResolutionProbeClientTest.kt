package me.pinfort.tsvideos.core.external.tool

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.core.external.shell.ShellClient
import me.pinfort.tsvideos.core.external.shell.ShellResult
import org.slf4j.Logger
import java.io.File

class VideoResolutionProbeClientTest :
    ExpectSpec({
        val shellClient = mockk<ShellClient>()
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
        val client = VideoResolutionProbeClient(shellClient, properties, mockk<Logger>(relaxed = true))
        val file = File("/tmp/video.mp4")

        fun result(
            exitCode: Int,
            stdout: String,
        ) = ShellResult(exitCode, stdout, "")

        expect("parses width and height") {
            every { shellClient.executeCapturingOutput(any(), any(), any()) } returns result(0, "1920,1080\n")
            client.probe(file) shouldBe VideoResolutionProbeClient.Resolution(1920, 1080)
        }

        expect("returns null when the file has no video stream") {
            every { shellClient.executeCapturingOutput(any(), any(), any()) } returns result(0, "")
            client.probe(file) shouldBe null
        }

        expect("returns null for unparseable or non-positive output") {
            every { shellClient.executeCapturingOutput(any(), any(), any()) } returns result(0, "N/A,N/A\n")
            client.probe(file) shouldBe null
            every { shellClient.executeCapturingOutput(any(), any(), any()) } returns result(0, "0,0\n")
            client.probe(file) shouldBe null
        }

        expect("returns null when ffprobe fails") {
            every { shellClient.executeCapturingOutput(any(), any(), any()) } returns result(1, "")
            client.probe(file) shouldBe null
        }
    })
