package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.PrintMessage
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
import me.pinfort.tsvideos.core.command.CreatedFileCommand
import me.pinfort.tsvideos.core.command.DuplicateProgramCommand
import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.component.DirectoryNameComponent
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramDetail
import me.pinfort.tsvideos.core.version.ApplicationVersion
import me.pinfort.tsvideos.manager.console.component.ProgramDetailToTextComponent
import me.pinfort.tsvideos.manager.console.component.TerminalTextColorComponent
import me.pinfort.tsvideos.manager.console.component.UserQuestionComponent
import java.nio.file.Path
import java.time.LocalDateTime

class TsVideosManagerTest :
    ExpectSpec({
        lateinit var programCommand: ProgramCommand
        lateinit var executedFileCommand: ExecutedFileCommand
        lateinit var createdFileCommand: CreatedFileCommand
        lateinit var duplicateProgramCommand: DuplicateProgramCommand
        lateinit var terminalTextColorComponent: TerminalTextColorComponent
        lateinit var userQuestionComponent: UserQuestionComponent
        lateinit var directoryNameComponent: DirectoryNameComponent
        lateinit var programDetailToTextComponent: ProgramDetailToTextComponent

        lateinit var search: Search
        lateinit var get: Get
        lateinit var delete: Delete
        lateinit var modify: Modify
        lateinit var duplicates: Duplicates
        lateinit var tsVideosManager: TsVideosManager

        beforeTest {
            clearAllMocks()

            programCommand = mockk()
            executedFileCommand = mockk()
            createdFileCommand = mockk()
            duplicateProgramCommand = mockk()
            terminalTextColorComponent = mockk()
            userQuestionComponent = mockk()
            directoryNameComponent = mockk()
            programDetailToTextComponent = mockk()

            search = Search(programCommand, terminalTextColorComponent)
            get = Get(programCommand, executedFileCommand, programDetailToTextComponent)
            delete = Delete(programCommand, userQuestionComponent, createdFileCommand)
            modify = Modify(programCommand, directoryNameComponent, userQuestionComponent)
            duplicates = Duplicates(duplicateProgramCommand, programCommand, terminalTextColorComponent)
            tsVideosManager = TsVideosManager(search, get, delete, modify, duplicates)
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
                createdFiles = listOf(),
            )

        context("execute") {
            expect("search") {
                every { programCommand.selectByName(any(), any(), any()) } returns listOf(dummyProgram)
                every { programCommand.hasTsFile(any()) } returns true
                every { terminalTextColorComponent.error(any()) } returns "1\t2023/01/01 00:00:00\tchannelName\t2\ttrue\ttitle"

                tsVideosManager.main(arrayOf("search", "-n", "test"))

                verify(exactly = 1) {
                    programCommand.selectByName("test", 500, 0)
                }
            }

            expect("get") {
                every { programCommand.findDetail(1) } returns dummyProgramDetail
                every { programDetailToTextComponent.convertConsole(any()) } returns "text"

                tsVideosManager.main(arrayOf("get", "program", "1"))

                verify(exactly = 1) {
                    programCommand.findDetail(1)
                }
            }

            expect("delete") {
                every { userQuestionComponent.askDefaultFalse(any()) } returns true
                every { programCommand.delete(any()) } just Runs
                every { programCommand.find(any()) } returns dummyProgram

                tsVideosManager.main(arrayOf("delete", "program", "1"))

                verify(exactly = 1) {
                    programCommand.delete(dummyProgram)
                }
            }

            expect("modify") {
                every { programCommand.find(any()) } returns dummyProgram
                every { programCommand.videoFiles(any()) } returns listOf(dummyCreatedFile)
                every { directoryNameComponent.replaceWithGivenDirectoryName(any(), any()) } returns Path.of("newPath")
                every { userQuestionComponent.askDefaultFalse(any()) } returns true
                every { programCommand.moveCreatedFiles(any(), any(), any()) } just Runs

                tsVideosManager.main(arrayOf("modify", "1", "directory_name", "newDirectory"))

                verify(exactly = 1) {
                    programCommand.moveCreatedFiles(dummyProgram, "newDirectory", false)
                }
            }

            expect("duplicates") {
                every { duplicateProgramCommand.detect(any()) } returns listOf()

                tsVideosManager.main(arrayOf("duplicates"))

                verify(exactly = 1) {
                    duplicateProgramCommand.detect(DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO)
                }
            }

            expect("version") {
                val exception = shouldThrow<PrintMessage> { tsVideosManager.parse(arrayOf("--version")) }

                exception.message shouldContain "tvmcli version ${ApplicationVersion.value}"
            }
        }
    })
