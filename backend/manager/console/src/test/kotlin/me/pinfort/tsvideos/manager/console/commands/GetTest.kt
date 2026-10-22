package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.catalog.api.ExecutedFileCommand
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.ProgramDetail
import me.pinfort.tsvideos.manager.console.component.ProgramDetailToTextComponent
import java.time.LocalDateTime

class GetTest :
    ExpectSpec({
        lateinit var programCommand: ProgramCommand
        lateinit var executedFileCommand: ExecutedFileCommand
        lateinit var programDetailToTextComponent: ProgramDetailToTextComponent
        lateinit var get: Get

        beforeTest {
            clearAllMocks()
            programCommand = mockk()
            executedFileCommand = mockk()
            programDetailToTextComponent = mockk()
            get = Get(programCommand, executedFileCommand, programDetailToTextComponent)
        }

        val programDetail =
            ProgramDetail(
                id = 1,
                name = "name",
                executedFileId = 2,
                status = Program.Status.REGISTERED,
                drops = 2,
                size = 3,
                recordedAt = LocalDateTime.of(2023, 1, 1, 0, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 4.0,
                createdFiles = listOf(),
            )

        val executedFile =
            ExecutedFile(
                id = 1,
                file = "file",
                drops = 2,
                size = 3,
                recordedAt = LocalDateTime.of(2023, 1, 1, 0, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 4.0,
                status = ExecutedFile.Status.REGISTERED,
            )

        context("execute") {
            context("program") {
                expect("success") {
                    every { programCommand.findDetail(1) } returns programDetail
                    every { programDetailToTextComponent.convertConsole(any()) } returns "text"

                    get.main(arrayOf("program", "1"))
                }

                expect("notFound") {
                    every { programCommand.findDetail(1) } returns null

                    get.test("program 1").statusCode shouldBe 3
                }
            }

            context("executedFile") {
                expect("success") {
                    every { executedFileCommand.find(1) } returns executedFile

                    get.main(arrayOf("executed_file", "1"))
                }

                expect("notFound") {
                    every { executedFileCommand.find(1) } returns null

                    get.test("executed_file 1").statusCode shouldBe 3
                }
            }
        }
    })
