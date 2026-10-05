package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.console.common.component.TerminalTextColorComponent
import me.pinfort.tsvideos.core.command.DuplicateProgramCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.domain.DuplicateGroup
import me.pinfort.tsvideos.core.domain.Program
import java.time.LocalDateTime

class DuplicatesTest :
    ExpectSpec({
        lateinit var duplicateProgramCommand: DuplicateProgramCommand
        lateinit var programCommand: ProgramCommand
        lateinit var terminalTextColorComponent: TerminalTextColorComponent
        lateinit var duplicates: Duplicates

        beforeTest {
            clearAllMocks()
            duplicateProgramCommand = mockk()
            programCommand = mockk()
            terminalTextColorComponent = mockk()
            duplicates = Duplicates(duplicateProgramCommand, programCommand, terminalTextColorComponent)
        }

        val dummyProgram =
            Program(
                id = 1,
                name = "name",
                executedFileId = 2,
                status = Program.Status.COMPLETED,
                drops = 0,
                size = 3,
                recordedAt = LocalDateTime.of(2025, 10, 5, 21, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 1800.0,
            )

        context("execute") {
            expect("success") {
                val group =
                    DuplicateGroup(
                        DuplicateGroup.Reason.SAME_BROADCAST,
                        listOf(dummyProgram, dummyProgram.copy(id = 2, drops = 3)),
                    )
                every { duplicateProgramCommand.detect(any()) } returns listOf(group)
                every { programCommand.hasTsFile(any()) } returns true
                every { terminalTextColorComponent.error(any()) } answers { firstArg() }

                duplicates.main(arrayOf())

                verify(exactly = 1) { duplicateProgramCommand.detect(DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO) }
                verify(exactly = 1) { terminalTextColorComponent.error(any()) }
            }

            expect("no duplicates") {
                every { duplicateProgramCommand.detect(any()) } returns listOf()

                duplicates.main(arrayOf("--min-overlap", "0.8"))

                verify(exactly = 1) { duplicateProgramCommand.detect(0.8) }
            }

            expect("min-overlap out of range") {
                shouldThrow<BadParameterValue> { duplicates.parse(arrayOf("--min-overlap", "0")) }
            }
        }
    })
