package me.pinfort.tsvideos.processing.api.tool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.catalog.domain.VideoChapter
import me.pinfort.tsvideos.processing.api.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.processing.api.shell.ShellClient
import me.pinfort.tsvideos.processing.api.shell.ShellResult
import me.pinfort.tsvideos.processing.domain.ShellCommandTimeoutException
import org.slf4j.Logger
import java.io.File

class ChapterProbeClientTest :
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
        val client = ChapterProbeClient(shellClient, properties, mockk<Logger>(relaxed = true))
        val file = File("/tmp/video.mp4")

        fun returns(
            stdout: String,
            exitCode: Int = 0,
        ) {
            every { shellClient.executeCapturingOutput(any(), any(), any()) } returns ShellResult(exitCode, stdout, "")
        }

        fun chapter(
            id: Int,
            start: String,
            end: String,
            title: String? = null,
        ): String {
            val tags = title?.let { ""","tags":{"title":"$it"}""" } ?: ""
            return """{"id":$id,"time_base":"1/1000","start_time":"$start","end_time":"$end"$tags}"""
        }

        fun chapters(vararg entries: String) = """{"chapters":[${entries.joinToString(",")}]}"""

        expect("runs ffprobe -show_chapters with JSON output on the file") {
            returns(chapters())
            client.probe(file)
            verify {
                shellClient.executeCapturingOutput(
                    file.parentFile,
                    listOf("ffprobe", "-v", "error", "-show_chapters", "-of", "json", file.absolutePath),
                    60,
                )
            }
        }

        expect("parses start, end and title, keeping duplicate and machine titles unchanged") {
            returns(
                chapters(
                    chapter(0, "0.000000", "5.739000", "A"),
                    chapter(1, "5.739000", "35.739000", "A"),
                    chapter(2, "35.739000", "95.000000", "CM"),
                    chapter(3, "95.000000", "185.000000", "D90Sec"),
                    chapter(4, "185.000000", "190.000000", "CM?"),
                ),
            )
            client.probe(file) shouldBe
                listOf(
                    VideoChapter(0, 0.0, 5.739, "A"),
                    VideoChapter(1, 5.739, 35.739, "A"),
                    VideoChapter(2, 35.739, 95.0, "CM"),
                    VideoChapter(3, 95.0, 185.0, "D90Sec"),
                    VideoChapter(4, 185.0, 190.0, "CM?"),
                )
        }

        expect("a file without chapters is probed with none") {
            returns(chapters())
            client.probe(file) shouldBe emptyList()
        }

        expect("a chapter without a title, or with a non-string title, has a null title") {
            returns(
                chapters(
                    chapter(0, "0", "1"),
                    """{"start_time":"1","end_time":"2","tags":{"title":7}}""",
                    """{"start_time":"2","end_time":"3","tags":{}}""",
                ),
            )
            client.probe(file) shouldBe
                listOf(VideoChapter(0, 0.0, 1.0, null), VideoChapter(1, 1.0, 2.0, null), VideoChapter(2, 2.0, 3.0, null))
        }

        expect("drops invalid timestamps, sorts stably by start and renumbers from 0") {
            returns(
                chapters(
                    chapter(0, "10.0", "20.0", "late"),
                    chapter(1, "-1.0", "5.0", "negative"),
                    chapter(2, "8.0", "4.0", "backwards"),
                    chapter(3, "nan", "4.0", "nan"),
                    chapter(4, "1.0", "inf", "infinite"),
                    chapter(5, "N/A", "4.0", "unparseable"),
                    """{"start_time":"3.0"}""",
                    chapter(6, "0.0", "10.0", "first"),
                    chapter(7, "10.0", "10.0", "same start, zero length"),
                    """{"start_time":2.5,"end_time":3.5,"tags":{"title":"numbers"}}""",
                ),
            )
            client.probe(file) shouldBe
                listOf(
                    VideoChapter(0, 0.0, 10.0, "first"),
                    VideoChapter(1, 2.5, 3.5, "numbers"),
                    VideoChapter(2, 10.0, 20.0, "late"),
                    VideoChapter(3, 10.0, 10.0, "same start, zero length"),
                )
        }

        expect("non-zero exit, malformed JSON or an unexpected shape is not probed") {
            returns(chapters(chapter(0, "0", "1")), exitCode = 1)
            client.probe(file) shouldBe null
            listOf("", "not json", "{}", """{"chapters":{}}""", """{"chapters":[1]}""", "[]").forEach {
                returns(it)
                client.probe(file) shouldBe null
            }
        }

        expect("bounds the logged stdout of unexpected output and stderr of a non-zero exit") {
            val logger = mockk<Logger>(relaxed = true)
            val loggingClient = ChapterProbeClient(shellClient, properties, logger)
            val messages = mutableListOf<String>()
            every { logger.warn(capture(messages)) } returns Unit
            val long = "x".repeat(10_000)

            returns(long)
            loggingClient.probe(file) shouldBe null
            every { shellClient.executeCapturingOutput(any(), any(), any()) } returns ShellResult(3, "", long)
            loggingClient.probe(file) shouldBe null

            messages.size shouldBe 2
            messages[0] shouldBe "Unexpected ffprobe chapter output, file=$file, stdout=${"x".repeat(500)}...(truncated, 10000 chars)"
            messages[1] shouldBe
                "ffprobe exited with non-zero code, file=$file, exitCode=3, stderr=${"x".repeat(500)}...(truncated, 10000 chars)"
        }

        expect("a timeout or a missing ffprobe is not probed") {
            every { shellClient.executeCapturingOutput(any(), any(), any()) } throws ShellCommandTimeoutException("timed out")
            client.probe(file) shouldBe null
            every { shellClient.executeCapturingOutput(any(), any(), any()) } throws java.io.IOException("missing")
            client.probe(file) shouldBe null
        }

        expect("does not swallow interruption as a failed probe") {
            every { shellClient.executeCapturingOutput(any(), any(), any()) } throws InterruptedException()
            shouldThrow<InterruptedException> { client.probe(file) }
            Thread.interrupted()
        }
    })
