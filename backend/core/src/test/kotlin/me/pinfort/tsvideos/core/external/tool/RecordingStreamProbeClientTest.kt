package me.pinfort.tsvideos.core.external.tool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.core.domain.RecordingStream
import me.pinfort.tsvideos.core.exception.ShellCommandTimeoutException
import me.pinfort.tsvideos.core.external.shell.ShellClient
import me.pinfort.tsvideos.core.external.shell.ShellResult
import java.io.File

class RecordingStreamProbeClientTest :
    ExpectSpec({
        val shell = mockk<ShellClient>()
        val config = mockk<ProcessorToolConfigurationProperties>()
        every { config.ffprobePath } returns "ffprobe"
        val client = RecordingStreamProbeClient(shell, config)
        val file = File("recordings/日本語 recording.m2ts").absoluteFile
        expect("known missing streams differ from unavailable results") {
            client.parse("""{"streams":[]} """) shouldBe emptySet()
            client.parse("{}") shouldBe null
            client.parse("""{"streams":[{}]}""") shouldBe null
            client.parse("""{"streams":[{"codec_type":"audio"},{"codec_type":"video"},{"codec_type":"data"}]}""") shouldBe
                setOf(RecordingStream.AUDIO, RecordingStream.VIDEO)
            // ARIB captions reported as data/unknown by some ffmpeg builds do not count as SUBTITLE.
            client.parse(
                """{"streams":[{"codec_type":"video"},{"codec_type":"unknown"},{"codec_type":"data"},{"codec_type":"subtitle"}]}""",
            ) shouldBe setOf(RecordingStream.VIDEO, RecordingStream.SUBTITLE)
            client.parse("""{"streams":[{"codec_type":"video"},{"codec_type":"unknown"},{"codec_type":"attachment"}]}""") shouldBe
                setOf(RecordingStream.VIDEO)
        }
        expect("arguments preserve paths and malformed output is unavailable") {
            every { shell.executeCapturingOutput(any(), any(), any()) } returns ShellResult(0, "not json", "secret")
            client.probe(file) shouldBe null
            verify {
                shell.executeCapturingOutput(
                    file.parentFile,
                    listOf("ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "json", file.absolutePath),
                    60,
                )
            }
            every { shell.executeCapturingOutput(any(), any(), any()) } returns ShellResult(1, "", "password")
            client.probe(file) shouldBe null
        }
        expect("a probe timeout is an unavailable measurement, not an interruption") {
            every { shell.executeCapturingOutput(any(), any(), any()) } throws ShellCommandTimeoutException("Command timed out after 60s")
            client.probe(file) shouldBe null
            Thread.currentThread().isInterrupted shouldBe false
        }
        expect("interruption propagates rather than becoming an unknown measurement") {
            every { shell.executeCapturingOutput(any(), any(), any()) } throws InterruptedException()
            try {
                shouldThrow<InterruptedException> { client.probe(file) }
                Thread.currentThread().isInterrupted shouldBe true
            } finally {
                Thread.interrupted()
            }
        }
    })
