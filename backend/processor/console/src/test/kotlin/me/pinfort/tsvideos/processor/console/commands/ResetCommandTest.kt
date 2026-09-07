package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.main
import io.kotest.core.spec.style.ExpectSpec
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.processor.console.component.UserQuestionComponent
import me.pinfort.tsvideos.processor.infrastructure.pipeline.ResetRunner
import java.nio.file.Path

class ResetCommandTest :
    ExpectSpec({
        lateinit var resetRunner: ResetRunner
        lateinit var userQuestionComponent: UserQuestionComponent
        lateinit var resetCommand: ResetCommand

        beforeTest {
            clearAllMocks()
            resetRunner = mockk()
            userQuestionComponent = mockk()
            resetCommand = ResetCommand(resetRunner, userQuestionComponent)
            every { resetRunner.run(any(), any()) } just Runs
        }

        context("run") {
            expect("runs the reset when the user confirms") {
                every { userQuestionComponent.askDefaultFalse(any()) } returns true

                resetCommand.main(arrayOf("/rec/rec.m2ts"))

                verify { resetRunner.run(Path.of("/rec/rec.m2ts"), false) }
            }

            expect("threads the dry-run flag through") {
                every { userQuestionComponent.askDefaultFalse(any()) } returns true

                resetCommand.main(arrayOf("--dry-run", "/rec/rec.m2ts"))

                verify { resetRunner.run(Path.of("/rec/rec.m2ts"), true) }
            }

            expect("does nothing when the user declines") {
                every { userQuestionComponent.askDefaultFalse(any()) } returns false

                resetCommand.main(arrayOf("/rec/rec.m2ts"))

                verify(exactly = 0) { resetRunner.run(any(), any()) }
            }
        }
    })
