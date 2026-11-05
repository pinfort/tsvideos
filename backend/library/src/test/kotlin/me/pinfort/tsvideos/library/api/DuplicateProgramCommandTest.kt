package me.pinfort.tsvideos.library.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.catalog.api.ProgramInventory
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import java.time.LocalDateTime

class DuplicateProgramCommandTest :
    ExpectSpec({
        lateinit var inventory: ProgramInventory
        lateinit var duplicateProgramCommand: DuplicateProgramCommand

        beforeTest {
            clearAllMocks()
            inventory = mockk()
            duplicateProgramCommand = DuplicateProgramCommand(inventory)
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
            title: String = "title",
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
                title = title,
                channelName = "channelName",
                duration = duration,
            )

        context("detect") {
            expect("loads the recorded programs from the catalog") {
                every { inventory.recordedPrograms() } returns listOf(program(1), program(2))

                val actual = duplicateProgramCommand.detect()

                actual shouldHaveSize 1
                actual[0].programs.map { it.id } shouldBe listOf(1L, 2L)
            }
        }

        context("group") {
            expect("same channel and start time is SAME_BROADCAST") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1), program(2)),
                        DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
                    )

                actual shouldHaveSize 1
                actual[0].reason shouldBe DuplicateGroup.Reason.SAME_BROADCAST
            }

            expect("same start time without duration is still SAME_BROADCAST") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1, duration = -1.0), program(2, duration = -1.0)),
                        DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
                    )

                actual shouldHaveSize 1
                actual[0].reason shouldBe DuplicateGroup.Reason.SAME_BROADCAST
            }

            expect("mostly overlapping recordings are OVERLAP") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1), program(2, recordedAt = base.plusMinutes(5))),
                        DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
                    )

                actual shouldHaveSize 1
                actual[0].reason shouldBe DuplicateGroup.Reason.OVERLAP
                actual[0].programs.map { it.id }.toSet() shouldBe setOf(1L, 2L)
            }

            expect("overlap exactly at the ratio is OVERLAP") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1), program(2, recordedAt = base.plusMinutes(15))),
                        0.5,
                    )

                actual shouldHaveSize 1
                actual[0].reason shouldBe DuplicateGroup.Reason.OVERLAP
            }

            expect("overlap just below the ratio is not duplicate") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1), program(2, recordedAt = base.plusMinutes(15).plusSeconds(1))),
                        0.5,
                    )

                actual.shouldBeEmpty()
            }

            expect("long recording covering two different programs is not duplicate") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(
                            program(1, duration = 7200.0),
                            program(2, duration = 3660.0),
                            program(3, recordedAt = base.plusMinutes(60), duration = 3660.0),
                        ),
                        DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
                    )

                actual.shouldBeEmpty()
            }

            expect("short partial recording inside a long one is not duplicate") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1, duration = 7200.0), program(2, recordedAt = base.plusMinutes(60), duration = 1800.0)),
                        DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
                    )

                actual.shouldBeEmpty()
            }

            expect("margin overlap between consecutive programs is not duplicate") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1, duration = 1830.0), program(2, recordedAt = base.plusMinutes(30))),
                        DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
                    )

                actual.shouldBeEmpty()
            }

            expect("same start time with very different durations is not duplicate") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1, duration = 7200.0), program(2, duration = 3600.0)),
                        DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
                    )

                actual.shouldBeEmpty()
            }

            expect("different channels are not duplicate") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1), program(2, channel = "BS101")),
                        DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
                    )

                actual.shouldBeEmpty()
            }

            expect("programs without executed file are ignored") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(program(1, recordedAt = LocalDateTime.MIN), program(2, recordedAt = LocalDateTime.MIN)),
                        DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
                    )

                actual.shouldBeEmpty()
            }

            expect("transitively overlapping recordings form one group") {
                val actual =
                    duplicateProgramCommand.group(
                        listOf(
                            program(1),
                            program(2, recordedAt = base.plusMinutes(4)),
                            program(3, recordedAt = base.plusMinutes(8)),
                        ),
                        DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
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
                        DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
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
                        DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
                    )

                actual.map { group -> group.programs.map { it.id }.toSet() } shouldBe listOf(setOf(3L, 4L), setOf(1L, 2L))
            }
        }

        context("rebroadcast candidates") {
            val episode = "番組 #1「副題」"

            fun group(programs: List<Program>) = duplicateProgramCommand.group(programs, DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO)

            expect("a same-channel single-episode rebroadcast is REBROADCAST with its comparison title and keep order") {
                val actual =
                    group(
                        listOf(
                            program(1, title = "$episode[新]", drops = 3),
                            program(2, recordedAt = base.plusDays(90), title = "$episode[再]", duration = 1801.0),
                        ),
                    )

                actual shouldHaveSize 1
                actual[0].reason shouldBe DuplicateGroup.Reason.REBROADCAST
                actual[0].comparisonTitle shouldBe "番組 #1 「副題」"
                // The existing keep order (drops first) picks the recommendation.
                actual[0].programs.map { it.id } shouldBe listOf(2L, 1L)
            }

            expect("existing SAME_BROADCAST/OVERLAP groups and recommendations are unchanged by the second stage") {
                val broadcastOnly =
                    listOf(
                        program(1, drops = 2),
                        program(2),
                        program(3, recordedAt = base.plusDays(1)),
                        program(4, recordedAt = base.plusDays(1).plusMinutes(4)),
                    )
                val withEpisodes = broadcastOnly.map { it.copy(title = episode) }
                val before = group(broadcastOnly)
                val after = group(withEpisodes)

                after.map { it.reason to it.programs.map { p -> p.id } } shouldBe before.map { it.reason to it.programs.map { p -> p.id } }
                after.map { it.reason } shouldBe listOf(DuplicateGroup.Reason.SAME_BROADCAST, DuplicateGroup.Reason.OVERLAP)
                after.all { it.comparisonTitle == null } shouldBe true
            }

            expect("mixed A/B same broadcast plus a later rebroadcast C returns only A/B (intended miss)") {
                val actual =
                    group(
                        listOf(
                            program(1, title = episode),
                            program(2, title = episode),
                            program(3, recordedAt = base.plusDays(90), title = episode),
                        ),
                    )

                actual.map { it.reason to it.programs.map { p -> p.id }.toSet() } shouldBe
                    listOf(DuplicateGroup.Reason.SAME_BROADCAST to setOf(1L, 2L))
            }

            expect("after the same-broadcast copy is removed, the remaining A and C become a rebroadcast candidate") {
                val actual = group(listOf(program(1, title = episode), program(3, recordedAt = base.plusDays(90), title = episode)))

                actual.single().reason shouldBe DuplicateGroup.Reason.REBROADCAST
            }

            expect("no program belongs to two groups and groups are ordered by their earliest recording") {
                val actual =
                    group(
                        listOf(
                            program(1, recordedAt = base.plusDays(2), title = "番組 #2"),
                            program(2, recordedAt = base.plusDays(92), title = "番組 #2"),
                            program(3, title = episode),
                            program(4, title = episode),
                            program(5, recordedAt = base.plusDays(1), title = "番組 #9"),
                            program(6, recordedAt = base.plusDays(91), title = "番組 #9"),
                        ),
                    )

                val ids = actual.flatMap { group -> group.programs.map { it.id } }
                ids.distinct() shouldBe ids
                actual.map { it.reason to it.programs.map { p -> p.id }.toSet() } shouldBe
                    listOf(
                        DuplicateGroup.Reason.SAME_BROADCAST to setOf(3L, 4L),
                        DuplicateGroup.Reason.REBROADCAST to setOf(5L, 6L),
                        DuplicateGroup.Reason.REBROADCAST to setOf(1L, 2L),
                    )
                group(actual.flatMap { it.programs }.reversed()).map { it.programs.map { p -> p.id }.toSet() } shouldBe
                    actual.map { it.programs.map { p -> p.id }.toSet() }
            }

            expect("--min-overlap does not change the rebroadcast duration rule") {
                val programs =
                    listOf(program(1, title = episode), program(2, recordedAt = base.plusDays(90), title = episode, duration = 1806.0))

                duplicateProgramCommand.group(programs, 0.1).shouldBeEmpty()
                duplicateProgramCommand.group(programs, 1.0).shouldBeEmpty()
            }
        }
    })
