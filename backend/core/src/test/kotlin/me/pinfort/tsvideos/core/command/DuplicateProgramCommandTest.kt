package me.pinfort.tsvideos.core.command

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.domain.DuplicateGroup
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.external.database.dto.ProgramDto
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import java.time.LocalDateTime

class DuplicateProgramCommandTest :
    ExpectSpec({
        lateinit var programMapper: ProgramMapper
        lateinit var duplicateProgramCommand: DuplicateProgramCommand

        beforeTest {
            clearAllMocks()
            programMapper = mockk()
            duplicateProgramCommand = DuplicateProgramCommand(programMapper)
        }

        val base = LocalDateTime.of(2025, 10, 5, 21, 0, 0)

        fun program(
            id: Long,
            recordedAt: LocalDateTime = base,
            duration: Double = 1800.0,
            channel: String = "GR27",
            drops: Int = 0,
            status: Program.Status = Program.Status.COMPLETED,
            size: Long = 100,
        ): Program =
            Program(
                id = id,
                name = "name$id",
                executedFileId = id,
                status = status,
                drops = drops,
                size = size,
                recordedAt = recordedAt,
                channel = channel,
                title = "title",
                channelName = "channelName",
                duration = duration,
            )

        context("detect") {
            expect("loads all programs from mapper") {
                every { programMapper.selectAll() } returns
                    listOf(
                        ProgramDto(1, "a", 1, ProgramDto.Status.COMPLETED, 0, 1, base, "GR27", "t", "c", 1800.0),
                        ProgramDto(2, "b", 2, ProgramDto.Status.COMPLETED, 0, 1, base, "GR27", "t", "c", 1800.0),
                    )

                val actual = duplicateProgramCommand.detect()

                actual shouldHaveSize 1
                actual[0].programs.map { it.id } shouldBe listOf(1L, 2L)
            }
        }

        context("group") {
            expect("same channel and start time is SAME_BROADCAST") {
                val actual = duplicateProgramCommand.group(listOf(program(1), program(2)), 0.5)

                actual shouldHaveSize 1
                actual[0].reason shouldBe DuplicateGroup.Reason.SAME_BROADCAST
            }

            expect("same start time without duration is still SAME_BROADCAST") {
                val actual = duplicateProgramCommand.group(listOf(program(1, duration = -1.0), program(2, duration = -1.0)), 0.5)

                actual shouldHaveSize 1
                actual[0].reason shouldBe DuplicateGroup.Reason.SAME_BROADCAST
            }

            expect("mostly overlapping recordings are OVERLAP") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1), program(2, recordedAt = base.plusMinutes(5))),
                        0.5,
                    )

                actual shouldHaveSize 1
                actual[0].reason shouldBe DuplicateGroup.Reason.OVERLAP
                actual[0].programs.map { it.id }.toSet() shouldBe setOf(1L, 2L)
            }

            expect("short recording inside a long one is OVERLAP") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1, duration = 7200.0), program(2, recordedAt = base.plusMinutes(60), duration = 1800.0)),
                        0.5,
                    )

                actual shouldHaveSize 1
            }

            expect("margin overlap between consecutive programs is not duplicate") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1, duration = 1830.0), program(2, recordedAt = base.plusMinutes(30))),
                        0.5,
                    )

                actual.shouldBeEmpty()
            }

            expect("different channels are not duplicate") {
                val actual = duplicateProgramCommand.group(listOf(program(1), program(2, channel = "BS101")), 0.5)

                actual.shouldBeEmpty()
            }

            expect("programs without executed file are ignored") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1, recordedAt = LocalDateTime.MIN), program(2, recordedAt = LocalDateTime.MIN)),
                        0.5,
                    )

                actual.shouldBeEmpty()
            }

            expect("transitively overlapping recordings form one group") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(
                            program(1),
                            program(2, recordedAt = base.plusMinutes(10)),
                            program(3, recordedAt = base.plusMinutes(20)),
                        ),
                        0.5,
                    )

                actual shouldHaveSize 1
                actual[0].programs shouldHaveSize 3
            }

            expect("programs are ordered by drops, status, duration, size, id") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(
                            program(1, drops = 5),
                            program(2, drops = -1),
                            program(3, status = Program.Status.ERROR),
                            program(4, status = Program.Status.REGISTERED),
                            program(5, duration = 1700.0),
                            program(6, size = 50),
                            program(7),
                            program(8),
                        ),
                        0.5,
                    )

                actual shouldHaveSize 1
                actual[0].programs.map { it.id } shouldBe listOf(7L, 8L, 6L, 5L, 4L, 3L, 1L, 2L)
                actual[0].recommended.id shouldBe 7L
                actual[0].others.map { it.id } shouldBe listOf(8L, 6L, 5L, 4L, 3L, 1L, 2L)
            }

            expect("groups are ordered by recorded time") {
                val later = base.plusDays(1)
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1, recordedAt = later), program(2, recordedAt = later), program(3), program(4)),
                        0.5,
                    )

                actual.map { group -> group.programs.map { it.id }.toSet() } shouldBe listOf(setOf(3L, 4L), setOf(1L, 2L))
            }
        }
    })
