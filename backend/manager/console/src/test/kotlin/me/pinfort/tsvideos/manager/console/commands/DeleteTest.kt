package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.main
import io.kotest.core.spec.style.ExpectSpec
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.console.common.component.UserQuestionComponent
import me.pinfort.tsvideos.core.command.CreatedFileCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramDetail
import me.pinfort.tsvideos.core.external.samba.SambaClient
import java.time.LocalDateTime

class DeleteTest :
    ExpectSpec({
        lateinit var programCommand: ProgramCommand
        lateinit var userQuestionComponent: UserQuestionComponent
        lateinit var createdFileCommand: CreatedFileCommand
        lateinit var delete: Delete

        beforeTest {
            clearAllMocks()

            programCommand = mockk()
            userQuestionComponent = mockk()
            createdFileCommand = mockk()
            delete = Delete(programCommand, userQuestionComponent, createdFileCommand)
        }

        val dummyProgram =
            Program(
                id = 1,
                name = "name",
                executedFileId = 2,
                status = Program.Status.REGISTERED,
                drops = 2,
                size = 3,
                recordedAt = LocalDateTime.MIN,
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 4.0,
            )

        val dummyCreatedFile =
            CreatedFile(
                id = 1,
                splittedFileId = 2,
                file = "path",
                size = 3,
                mime = "video/vnd.dlna.mpeg-tts",
                encoding = "encoding",
                status = CreatedFile.Status.REGISTERED,
            )

        val dummyProgramDetail =
            ProgramDetail(
                id = 1,
                name = "name",
                executedFileId = 2,
                status = Program.Status.REGISTERED,
                drops = 2,
                size = 3,
                recordedAt = LocalDateTime.MIN,
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 4.0,
                createdFiles =
                    listOf(
                        dummyCreatedFile,
                    ),
            )

        context("execute") {
            context("program") {
                expect("success") {
                    every { userQuestionComponent.askDefaultFalse(any()) } returns true
                    every { programCommand.delete(any()) } just Runs
                    every { programCommand.find(any()) } returns dummyProgram

                    delete.main(arrayOf("program", "1"))

                    verify(exactly = 1) {
                        programCommand.delete(dummyProgram)
                    }
                }

                expect("canceled") {
                    every { userQuestionComponent.askDefaultFalse(any()) } returns false
                    every { programCommand.find(any()) } returns dummyProgram

                    delete.main(arrayOf("program", "1"))

                    verify(exactly = 0) {
                        programCommand.delete(dummyProgram)
                    }
                }
            }

            context("ts_files") {
                expect("success") {
                    every { userQuestionComponent.askDefaultFalse(any()) } returns true
                    every { programCommand.findDetail(any()) } returns dummyProgramDetail
                    every { createdFileCommand.delete(any()) } returns SambaClient.NasType.VIDEO_STORE_NAS

                    delete.main(arrayOf("ts_files", "1"))

                    verify(exactly = 1) {
                        createdFileCommand.delete(dummyCreatedFile)
                    }
                }

                expect("canceled") {
                    every { userQuestionComponent.askDefaultFalse(any()) } returns false
                    every { programCommand.findDetail(any()) } returns dummyProgramDetail

                    delete.main(arrayOf("ts_files", "1"))

                    verify(exactly = 0) {
                        createdFileCommand.delete(dummyCreatedFile)
                    }
                }
            }
        }
    })
