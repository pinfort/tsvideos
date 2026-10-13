package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.processor.console.parseProcessor
import me.pinfort.tsvideos.processor.infrastructure.pipeline.WatchRunner

class WatchCommandTest :
    ExpectSpec({
        expect("completion policy is explicit and limits reject invalid input before monitoring") {
            listOf(
                listOf("recordings"),
                listOf("recordings", "--completion", "stable", "--stable-seconds", "0"),
                listOf("recordings", "--completion", "marker", "--marker-suffix", "../bad"),
            ).forEach { args ->
                val runner = mockk<WatchRunner>()
                shouldThrow<CliktError> { WatchCommand(runner).parseProcessor(args) }.statusCode shouldBe 2
                verify(exactly = 0) { runner.run(any(), any(), any(), any(), any(), any()) }
            }
        }
        expect("watch options and dry-run are passed together and capacity exit remains4") {
            val runner = mockk<WatchRunner>()
            every { runner.run(any(), any(), any(), any(), any(), any()) } returns 4
            val error =
                shouldThrow<CliktError> {
                    WatchCommand(
                        runner,
                    ).parseProcessor(
                        listOf("recordings", "--completion", "marker", "--extension", ".ts", "--poll-seconds", "1", "--dry-run"),
                    )
                }
            error.statusCode shouldBe 4
            verify { runner.run(match { it.extensions == setOf(".ts") && it.pollSeconds == 1L }, true, any(), any(), any(), any()) }
        }
    })
