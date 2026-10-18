package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.processor.console.parseProcessor
import me.pinfort.tsvideos.processor.infrastructure.pipeline.PathProcessingRunner
import java.nio.file.Path

class ProcessCommandTest :
    ExpectSpec({
        lateinit var pathProcessingRunner: PathProcessingRunner
        lateinit var processCommand: ProcessCommand

        beforeTest {
            clearAllMocks()
            pathProcessingRunner = mockk()
            processCommand = ProcessCommand(pathProcessingRunner)
        }

        expect("quarantine exit four aggregates all paths and dominates another failure") {
            every { pathProcessingRunner.processPath(any(), any(), any(), any(), any(), any()) } returnsMany
                listOf(
                    PathProcessingRunner.Summary(quarantined = 1),
                    PathProcessingRunner.Summary(failed = 1),
                )
            val result = processCommand.test(listOf("/one", "/two")) { processCommand.parseProcessor(it) }
            result.statusCode shouldBe 4
            verify(exactly = 2) { pathProcessingRunner.processPath(any(), any(), any(), any(), any(), any()) }
        }
        expect("ordinary failure exits one and interruption propagates to ConsoleRunner") {
            every { pathProcessingRunner.processPath(any(), any(), any(), any(), any(), any()) } returns
                PathProcessingRunner.Summary(failed = 1)
            processCommand.test(listOf("/one")) { processCommand.parseProcessor(it) }.statusCode shouldBe 1
            every { pathProcessingRunner.processPath(any(), any(), any(), any(), any(), any()) } throws InterruptedException()
            shouldThrow<InterruptedException> { processCommand.test(listOf("/one")) { processCommand.parseProcessor(it) } }
        }
        context("run") {
            expect("processes every positional path argument") {
                every { pathProcessingRunner.processPath(any(), any(), any(), any(), any(), any()) } returns
                    PathProcessingRunner.Summary(processed = 1)

                processCommand.main(arrayOf("/path/one", "/path/two"))

                verify { pathProcessingRunner.processPath(Path.of("/path/one"), false, any(), any(), any(), any()) }
                verify { pathProcessingRunner.processPath(Path.of("/path/two"), false, any(), any(), any(), any()) }
            }

            expect("threads the dry-run flag through") {
                every { pathProcessingRunner.processPath(any(), any(), any(), any(), any(), any()) } returns
                    PathProcessingRunner.Summary(processed = 1)

                processCommand.main(arrayOf("--dry-run", "/path/one"))

                verify { pathProcessingRunner.processPath(Path.of("/path/one"), true, any(), any(), any(), any()) }
            }
        }
    })
