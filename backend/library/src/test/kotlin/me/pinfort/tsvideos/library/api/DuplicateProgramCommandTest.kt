package me.pinfort.tsvideos.library.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

        context("detectOnChannel") {
            val episode = "番組 #1「副題」"

            fun shape(groups: List<DuplicateGroup>) = groups.map { Triple(it.reason, it.programs.map { p -> p.id }, it.comparisonTitle) }

            /** Mocks the catalog so each channel query returns exactly that channel's rows of [all]. */
            fun library(all: List<Program>) {
                every { inventory.recordedPrograms() } returns all
                every { inventory.recordedProgramsOnChannel(any()) } answers { all.filter { it.channel == firstArg<String>() } }
            }

            fun assertParity(all: List<Program>) {
                library(all)
                val detected = duplicateProgramCommand.detect()
                // OTHER_CHANNEL groups (#209) span channels and are never returned per channel; every one has one member
                // per channel. The other reasons are exactly the per-channel results.
                val (otherChannel, full) = detected.partition { it.reason == DuplicateGroup.Reason.OTHER_CHANNEL }
                otherChannel.all { group -> group.programs.distinctBy { it.channel }.size == group.programs.size } shouldBe true
                val channels = all.map { it.channel }.distinct()
                channels.forEach { channel ->
                    shape(duplicateProgramCommand.detectOnChannel(channel)) shouldBe
                        shape(full.filter { group -> group.programs.all { it.channel == channel } })
                }
                // Every other detected group lies on one channel, so the per-channel results together are the whole result.
                full.all { group ->
                    group.programs
                        .map { it.channel }
                        .distinct()
                        .size == 1
                } shouldBe true
                channels.sumOf { duplicateProgramCommand.detectOnChannel(it).size } shouldBe full.size
            }

            expect("reads only the given channel from the catalog, never the whole library") {
                library(listOf(program(1), program(2), program(3, channel = "BS101"), program(4, channel = "BS101")))

                val actual = duplicateProgramCommand.detectOnChannel("BS101")

                actual.map { group -> group.programs.map { it.id } } shouldBe listOf(listOf(3L, 4L))
                verify(exactly = 1) { inventory.recordedProgramsOnChannel("BS101") }
                verify(exactly = 0) { inventory.recordedPrograms() }
            }

            expect("matches detect() on each channel for same-broadcast, overlap and rebroadcast groups including mixed and chains") {
                val day = base.plusDays(90)
                assertParity(
                    listOf("GR27", "BS101").flatMapIndexed { index, channel ->
                        val offset = index * 100L
                        listOf(
                            // Same broadcast whose members have different titles: a title-scoped read would split it.
                            program(offset + 1, channel = channel, title = "A"),
                            program(offset + 2, channel = channel, title = "B"),
                            // Transitive overlap chain across titles.
                            program(offset + 3, base.plusDays(1), channel = channel, title = "番組 #5"),
                            program(offset + 4, base.plusDays(1).plusMinutes(4), channel = channel, title = "other"),
                            program(offset + 5, base.plusDays(1).plusMinutes(8), channel = channel, title = "番組 #5"),
                            // Mixed: A/B same broadcast plus a rebroadcast C of the same episode -> only A/B.
                            program(offset + 6, base.plusDays(2), channel = channel, title = episode),
                            program(offset + 7, base.plusDays(2), channel = channel, title = episode),
                            program(offset + 8, day, channel = channel, title = episode),
                            // Rebroadcast pair.
                            program(offset + 9, base.plusDays(3), channel = channel, title = "番組 #2[新]"),
                            program(offset + 10, day.plusDays(3), channel = channel, title = "番組 #2[再]", duration = 1801.0),
                            // Non-clique chain (1800-1803-1806): left out entirely.
                            program(offset + 11, base.plusDays(4), channel = channel, title = "番組 #3"),
                            program(offset + 12, base.plusDays(40), channel = channel, title = "番組 #3", duration = 1803.0),
                            program(offset + 13, base.plusDays(80), channel = channel, title = "番組 #3", duration = 1806.0),
                        )
                    } +
                        // The same episode on another channel only: never grouped with the channels above.
                        listOf(program(500, base.plusDays(5), channel = "BS103", title = "番組 #2[再]")),
                )
                // Only the leftover C of the mixed case pairs across channels; 500's same-channel peers are already taken.
                duplicateProgramCommand
                    .detect()
                    .filter { it.reason == DuplicateGroup.Reason.OTHER_CHANNEL }
                    .map { group -> group.programs.map { it.id }.toSet() } shouldBe listOf(setOf(8L, 108L))
            }

            expect("matches detect() on each channel of a large multi-channel library") {
                val random = kotlin.random.Random(208)
                val channels = listOf("GR21", "GR22", "GR27", "BS101", "BS103", "BS141")
                val markers = listOf("", "[新]", "[再]", "[字]")
                val all =
                    (1L..3000L).map { id ->
                        // Four 30-minute slots a day with a few minutes of jitter: same broadcasts, overlaps and rebroadcasts.
                        val minutes = random.nextLong(0, 4) * 30 + random.nextLong(0, 6)
                        program(
                            id,
                            recordedAt = base.plusDays(random.nextLong(0, 120)).plusMinutes(minutes),
                            duration = 1800.0 + random.nextInt(0, 9),
                            channel = channels[random.nextInt(channels.size)],
                            title = "シリーズ${random.nextInt(0, 60)} #${random.nextInt(1, 6)}" + markers[random.nextInt(markers.size)],
                        )
                    }

                assertParity(all)
                library(all)
                duplicateProgramCommand.detect().map { it.reason }.toSet().containsAll(
                    listOf(DuplicateGroup.Reason.SAME_BROADCAST, DuplicateGroup.Reason.OVERLAP, DuplicateGroup.Reason.REBROADCAST),
                ) shouldBe true
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

        context("other-channel candidates (#209)") {
            val episode = "番組 #1「副題」"

            fun group(programs: List<Program>) = duplicateProgramCommand.group(programs, DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO)

            fun shape(groups: List<DuplicateGroup>) = groups.map { it.reason to it.programs.map { p -> p.id } }

            expect("the same single episode on another channel is OTHER_CHANNEL with its comparison title and keep order") {
                val actual =
                    group(
                        listOf(
                            program(1, title = "$episode[新]", channel = "GR16", drops = 3),
                            program(2, recordedAt = base.plusDays(400), title = "[再]$episode", channel = "BSBS01_0", duration = 1801.0),
                        ),
                    )

                actual.single().reason shouldBe DuplicateGroup.Reason.OTHER_CHANNEL
                actual.single().comparisonTitle shouldBe "番組 #1 「副題」"
                // The existing keep order (drops first) picks the recommendation; quality is not inferred from the channel.
                actual.single().programs.map { it.id } shouldBe listOf(2L, 1L)
            }

            expect("a simultaneous broadcast on two channels is an OTHER_CHANNEL candidate, not SAME_BROADCAST") {
                group(listOf(program(1, title = episode), program(2, title = episode, channel = "BS101"))).map { it.reason } shouldBe
                    listOf(DuplicateGroup.Reason.OTHER_CHANNEL)
            }

            expect("earlier stages are unchanged and only their leftovers can pair across channels") {
                val earlier =
                    listOf(
                        // Same broadcast A/B, and C of the same episode on another channel: A/B only (intended miss).
                        program(1, title = episode),
                        program(2, title = episode),
                        program(3, recordedAt = base.plusDays(30), title = episode, channel = "BS101"),
                        // Rebroadcast pair D/E, and F of that episode on another channel: D/E only.
                        program(4, recordedAt = base.plusDays(1), title = "番組 #2"),
                        program(5, recordedAt = base.plusDays(91), title = "番組 #2"),
                        program(6, recordedAt = base.plusDays(2), title = "番組 #2", channel = "BS101"),
                    )
                val actual = group(earlier)

                shape(actual.filter { it.reason != DuplicateGroup.Reason.OTHER_CHANNEL }) shouldBe
                    shape(group(earlier.filter { it.channel == "GR27" }))
                actual.map { it.reason to it.programs.map { p -> p.id }.toSet() } shouldBe
                    listOf(DuplicateGroup.Reason.SAME_BROADCAST to setOf(1L, 2L), DuplicateGroup.Reason.REBROADCAST to setOf(4L, 5L))
                val ids = actual.flatMap { g -> g.programs.map { it.id } }
                ids.distinct() shouldBe ids
            }

            expect("a component with a repeated channel or a non-clique chain is left out entirely, in any input order") {
                // 1 and 3 share GR27 but are not connected (3 is a third of 1's overlap and length), so 1-2 and 3-4 are
                // two separate groups: a same-channel recording outside the component does not affect it.
                val separate =
                    listOf(
                        program(1, title = episode),
                        program(2, recordedAt = base.plusDays(7), title = episode, channel = "BS101"),
                        program(3, recordedAt = base.plusMinutes(1), title = episode, duration = 600.0),
                        program(4, recordedAt = base.plusDays(9), title = episode, channel = "BS103", duration = 600.0),
                    )
                group(separate).map { it.programs.map { p -> p.id }.toSet() } shouldBe listOf(setOf(1L, 2L), setOf(3L, 4L))
                val sameChannelPair =
                    listOf(
                        program(1, title = episode),
                        program(2, recordedAt = base.plusDays(7), title = episode, channel = "BS101"),
                        // Overlaps 1 on GR27 too little for OVERLAP (0.67) and too much for a rebroadcast.
                        program(3, recordedAt = base.plusMinutes(10), title = episode),
                    )
                // 1-2 and 2-3 are compatible across channels, but 1 and 3 share GR27: the whole component is withheld.
                group(sameChannelPair).shouldBeEmpty()
                group(sameChannelPair.reversed()).shouldBeEmpty()
                val chain =
                    listOf(
                        program(1, title = episode, channel = "A"),
                        program(2, recordedAt = base.plusDays(1), title = episode, channel = "B", duration = 1804.0),
                        program(3, recordedAt = base.plusDays(2), title = episode, channel = "C", duration = 1808.0),
                    )
                group(chain).shouldBeEmpty()
                group(listOf(chain[2], chain[0], chain[1])).shouldBeEmpty()
            }

            expect("three channels that all agree form one group and the result does not depend on input order") {
                val programs =
                    listOf(
                        program(1, title = episode, channel = "A"),
                        program(2, recordedAt = base.plusDays(1), title = episode, channel = "B", duration = 1802.0),
                        program(3, recordedAt = base.plusDays(2), title = episode, channel = "C", duration = 1804.0),
                        program(4, recordedAt = base.plusDays(3), title = "番組 #2", channel = "A"),
                        program(5, recordedAt = base.plusDays(4), title = "番組 #2", channel = "B"),
                    )
                val actual = group(programs)

                actual.map { it.programs.map { p -> p.id }.toSet() } shouldBe listOf(setOf(1L, 2L, 3L), setOf(4L, 5L))
                shape(group(programs.reversed())) shouldBe shape(actual)
                shape(group(programs.shuffled(kotlin.random.Random(209)))) shouldBe shape(actual)
            }

            expect("different episodes, seasons, editions, subtitles and multi-episode titles never pair across channels") {
                listOf(
                    "番組 #1" to "番組 #2",
                    "番組 第1期 #1" to "番組 第2期 #1",
                    "番組 #1「副題」" to "番組 #1「別の副題」",
                    // An omitted subtitle is not a wildcard.
                    "番組 #1" to "番組 #1「副題」",
                    "番組 #1[字]" to "番組 #1[吹]",
                    "番組 #1,2" to "番組 #1,2",
                    "番組 第1話〜3話" to "番組 第1話〜3話",
                    "番組" to "番組",
                ).forEach { (a, b) ->
                    group(listOf(program(1, title = a), program(2, recordedAt = base.plusDays(7), title = b, channel = "BS101")))
                        .shouldBeEmpty()
                }
            }

            expect("the duration rule is bounded by both 5 seconds and 0.995, and needs finite positive durations") {
                fun pair(
                    a: Double,
                    b: Double,
                ): List<DuplicateGroup.Reason> {
                    val programs =
                        listOf(
                            program(1, title = episode, duration = a),
                            program(2, recordedAt = base.plusDays(7), title = episode, channel = "BS101", duration = b),
                        )
                    return group(programs).map { it.reason }
                }

                pair(1800.0, 1805.0) shouldBe listOf(DuplicateGroup.Reason.OTHER_CHANNEL)
                pair(1800.0, 1805.01).shouldBeEmpty()
                pair(1000.0, 995.0) shouldBe listOf(DuplicateGroup.Reason.OTHER_CHANNEL)
                pair(1000.0, 994.99).shouldBeEmpty()
                pair(300.0, 298.0).shouldBeEmpty()
                // マリッジトキシン #01's durations (121.4 s apart on CSCS16/GR17; the real titles also differ by a subtitle).
                pair(1807.14, 1685.737033).shouldBeEmpty()
                pair(0.0, 0.0).shouldBeEmpty()
                pair(-1.0, -1.0).shouldBeEmpty()
                pair(Double.NaN, Double.NaN).shouldBeEmpty()
                pair(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY).shouldBeEmpty()
            }

            expect("detectOnChannel never returns an other-channel group") {
                val all = listOf(program(1, title = episode), program(2, recordedAt = base.plusDays(7), title = episode, channel = "BS101"))
                every { inventory.recordedPrograms() } returns all
                every { inventory.recordedProgramsOnChannel(any()) } answers { all.filter { it.channel == firstArg<String>() } }

                duplicateProgramCommand.detect().single().reason shouldBe DuplicateGroup.Reason.OTHER_CHANNEL
                duplicateProgramCommand.detectOnChannel("GR27").shouldBeEmpty()
                duplicateProgramCommand.detectOnChannel("BS101").shouldBeEmpty()
            }
        }
    })
