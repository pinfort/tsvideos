package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.main
import io.kotest.core.spec.style.ExpectSpec
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
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

        context("run") {
            expect("processes every positional path argument") {
                every { pathProcessingRunner.processPath(any(), any(), any(), any(), any()) } just Runs

                processCommand.main(arrayOf("/path/one", "/path/two"))

                verify { pathProcessingRunner.processPath(Path.of("/path/one"), false, any(), any(), any()) }
                verify { pathProcessingRunner.processPath(Path.of("/path/two"), false, any(), any(), any()) }
            }

            expect("threads the dry-run flag through") {
                every { pathProcessingRunner.processPath(any(), any(), any(), any(), any()) } just Runs

                processCommand.main(arrayOf("--dry-run", "/path/one"))

                verify { pathProcessingRunner.processPath(Path.of("/path/one"), true, any(), any(), any()) }
            }
        }
    })
