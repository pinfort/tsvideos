package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.main
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

        expect("batch failures return aggregate nonzero status after processing completes") {
            every { pathProcessingRunner.processPaths(any(), any(), any(), any()) } returns PathProcessingRunner.BatchResult(3, 1)
            val error =
                io.kotest.assertions.throwables.shouldThrow<com.github.ajalt.clikt.core.CliktError> {
                    processCommand.parseProcessor(listOf("/path"))
                }
            error.statusCode shouldBe 1
        }
        context("run") {
            expect("processes every positional path argument") {
                every { pathProcessingRunner.processPaths(any(), any(), any(), any()) } returns PathProcessingRunner.BatchResult(2, 0)

                processCommand.main(arrayOf("/path/one", "/path/two"))

                verify { pathProcessingRunner.processPaths(listOf(Path.of("/path/one"), Path.of("/path/two")), false, any(), any()) }
            }

            expect("threads the dry-run flag through") {
                every { pathProcessingRunner.processPaths(any(), any(), any(), any()) } returns PathProcessingRunner.BatchResult(2, 0)

                processCommand.main(arrayOf("--dry-run", "/path/one"))

                verify { pathProcessingRunner.processPaths(listOf(Path.of("/path/one")), true, any(), any()) }
            }
        }
    })
