package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.main
import io.kotest.core.spec.style.ExpectSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.ProgramSearchPage
import me.pinfort.tsvideos.console.common.component.TerminalTextColorComponent
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
                every { programCommand.search(any()) } returns ProgramSearchPage(listOf(dummyProgram), 1, 100, 0)
                every { programCommand.hasTsFile(any()) } returns true
                every { terminalTextColorComponent.error(any()) } returns "1\t2023/01/01 00:00:00\tchannelName\t3\ttrue\ttitle"

                search.main(arrayOf("-n", "test"))
            }
        }
    })
