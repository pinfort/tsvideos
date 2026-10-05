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
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.component.DirectoryNameComponent
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.Program
import java.nio.file.Path
import java.time.LocalDateTime

class ModifyTest :
    ExpectSpec({
        lateinit var programCommand: ProgramCommand
        lateinit var directoryNameComponent: DirectoryNameComponent
        lateinit var userQuestionComponent: UserQuestionComponent
        lateinit var modify: Modify

        beforeTest {
            clearAllMocks()
            programCommand = mockk()
            directoryNameComponent = mockk()
            userQuestionComponent = mockk()
            modify = Modify(programCommand, directoryNameComponent, userQuestionComponent)
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
                file = "foo/bar/baz/1.ts",
                size = 3,
                mime = "video/vnd.dlna.mpeg-tts",
                encoding = "encoding",
                status = CreatedFile.Status.REGISTERED,
            )

        context("execute") {
            context("directory_name") {
                expect("success") {
                    every { programCommand.find(any()) } returns dummyProgram
                    every { programCommand.videoFiles(any()) } returns listOf(dummyCreatedFile)
                    every { directoryNameComponent.replaceWithGivenDirectoryName(any(), any()) } returns Path.of("newPath")
                    every { userQuestionComponent.askDefaultFalse(any()) } returns true
                    every { programCommand.moveCreatedFiles(any(), any(), any()) } just Runs

                    modify.main(arrayOf("1", "directory_name", "newDirectory"))

                    verify(exactly = 1) {
                        programCommand.moveCreatedFiles(dummyProgram, "newDirectory", false)
                    }
                }

                expect("canceled") {
                    every { programCommand.find(any()) } returns dummyProgram
                    every { programCommand.videoFiles(any()) } returns listOf(dummyCreatedFile)
                    every { directoryNameComponent.replaceWithGivenDirectoryName(any(), any()) } returns Path.of("newPath")
                    every { userQuestionComponent.askDefaultFalse(any()) } returns false

                    modify.main(arrayOf("1", "directory_name", "newDirectory"))

                    verify(exactly = 0) {
                        programCommand.moveCreatedFiles(dummyProgram, "newDirectory", false)
                    }
                }
            }
        }
    })
