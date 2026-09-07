package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.version.ApplicationVersion
import me.pinfort.tsvideos.processor.console.component.UserQuestionComponent
import me.pinfort.tsvideos.processor.infrastructure.pipeline.AfterEncodeRunner
import me.pinfort.tsvideos.processor.infrastructure.pipeline.PathProcessingRunner
import me.pinfort.tsvideos.processor.infrastructure.pipeline.ResetRunner
import java.nio.file.Path

class TsVideosProcessorTest :
    ExpectSpec({
        lateinit var pathProcessingRunner: PathProcessingRunner
        lateinit var afterEncodeRunner: AfterEncodeRunner
        lateinit var resetRunner: ResetRunner
        lateinit var userQuestionComponent: UserQuestionComponent
        lateinit var tsVideosProcessor: TsVideosProcessor

        beforeTest {
            clearAllMocks()
            pathProcessingRunner = mockk()
            afterEncodeRunner = mockk()
            resetRunner = mockk()
            userQuestionComponent = mockk()
            tsVideosProcessor =
                TsVideosProcessor(
                    ProcessCommand(pathProcessingRunner),
                    AfterEncodeCommand(afterEncodeRunner),
                    ResetCommand(resetRunner, userQuestionComponent),
                )
        }

        context("subcommands") {
            expect("routes process to the path processing runner") {
                every { pathProcessingRunner.processPath(any(), any(), any(), any()) } just Runs

                tsVideosProcessor.main(arrayOf("process", "/path/one"))

                verify { pathProcessingRunner.processPath(Path.of("/path/one"), false, any(), any()) }
            }

            expect("routes after-encode to the after encode runner") {
                every { afterEncodeRunner.run(any(), any(), any()) } just Runs
                tsVideosProcessor.context { readEnvvar = { null } }

                tsVideosProcessor.main(arrayOf("after-encode", "--item-id", "1", "--in-path", "/rec/a.m2ts"))

                verify { afterEncodeRunner.run(match { it.itemId == 1 }, false, any()) }
            }

            expect("routes reset to the reset runner") {
                every { userQuestionComponent.askDefaultFalse(any()) } returns true
                every { resetRunner.run(any(), any()) } just Runs

                tsVideosProcessor.main(arrayOf("reset", "/rec/rec.m2ts"))

                verify { resetRunner.run(Path.of("/rec/rec.m2ts"), false) }
            }

            expect("prints the version and runs no subcommand") {
                val exception = shouldThrow<PrintMessage> { tsVideosProcessor.parse(arrayOf("--version")) }

                exception.message shouldContain "tvpcli version ${ApplicationVersion.value}"
                verify(exactly = 0) { pathProcessingRunner.processPath(any(), any(), any(), any()) }
                verify(exactly = 0) { afterEncodeRunner.run(any(), any(), any()) }
            }
        }
    })
