package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.main
import io.kotest.core.spec.style.ExpectSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.console.common.component.TerminalTextColorComponent
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.domain.Program
import java.time.LocalDateTime

class SearchTest :
    ExpectSpec({
        lateinit var programCommand: ProgramCommand
        lateinit var terminalTextColorComponent: TerminalTextColorComponent
        lateinit var search: Search

        beforeTest {
            clearAllMocks()
            programCommand = mockk()
            terminalTextColorComponent = mockk()
            search = Search(programCommand, terminalTextColorComponent)
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

        context("execute") {
            expect("success") {
                every { programCommand.selectByName(any(), any(), any()) } returns listOf(dummyProgram)
                every { programCommand.hasTsFile(any()) } returns true
                every { terminalTextColorComponent.error(any()) } returns "1\t2023/01/01 00:00:00\tchannelName\t3\ttrue\ttitle"

                search.main(arrayOf("-n", "test"))
            }
        }
    })
