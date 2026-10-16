package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.command.LibraryStatsCommand
import me.pinfort.tsvideos.core.domain.LibraryStats
import me.pinfort.tsvideos.core.domain.LibraryStatsGroup
import me.pinfort.tsvideos.core.domain.LibraryStatsQuery
import me.pinfort.tsvideos.core.domain.LibraryStatsTotals
import me.pinfort.tsvideos.manager.console.parseManager
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime

class StatsTest :
    ExpectSpec({
        val empty = LibraryStatsTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        expect("empty JSON envelope has integer zero totals and physical null") {
            val command = mockk<LibraryStatsCommand>()
            every { command.summarize(any()) } returns LibraryStats(empty, emptyList())
            val result = Stats(command).invoke("--json")
            result.statusCode shouldBe 0
            result.stderr shouldBe ""
            val json = JsonMapper.builder().build().readTree(result.stdout)
            json["schemaVersion"].asInt() shouldBe 1
            json["stats"]["totals"]["registeredBytes"].asLong() shouldBe 0L
            json["stats"]["totals"]["verifiedPhysicalBytes"].isNull shouldBe true
            json["stats"]["groups"].size() shouldBe 0
        }
        expect("group and range options reach core with local bounds") {
            val command = mockk<LibraryStatsCommand>()
            every { command.summarize(any()) } returns LibraryStats(empty, listOf(LibraryStatsGroup("日本語", empty)))
            val result =
                Stats(
                    command,
                ).invoke("--group-by channel --recorded-from 2026-01-01T00:00:00 --recorded-to 2026-02-01T00:00:00 --json")
            result.statusCode shouldBe 0
            verify {
                command.summarize(
                    LibraryStatsQuery(
                        LibraryStatsQuery.Group.CHANNEL,
                        LocalDateTime.parse("2026-01-01T00:00:00"),
                        LocalDateTime.parse("2026-02-01T00:00:00"),
                    ),
                )
            }
            JsonMapper
                .builder()
                .build()
                .readTree(result.stdout)["stats"]["groups"][0]["key"]
                .asString() shouldBe "日本語"
        }
        expect("JSON key set is pinned for schemaVersion 1") {
            val command = mockk<LibraryStatsCommand>()
            every { command.summarize(any()) } returns LibraryStats(empty, listOf(LibraryStatsGroup("2026-01", empty)))
            val stats = JsonMapper.builder().build().readTree(Stats(command).invoke("--group-by month --json").stdout)["stats"]
            val totalsKeys =
                setOf(
                    "programCount",
                    "failedProgramCount",
                    "droppedFrameCount",
                    "unknownDropsProgramCount",
                    "registeredFileCount",
                    "registeredBytes",
                    "originalTsBytes",
                    "encodedBytes",
                    "otherBytes",
                    "movedFileBytes",
                    "pendingFileBytes",
                    "verifiedPhysicalBytes",
                )
            stats.propertyNames().toSet() shouldBe setOf("groupBy", "recordedFrom", "recordedTo", "totals", "groups")
            stats["totals"].propertyNames().toSet() shouldBe totalsKeys
            stats["groups"][0].propertyNames().toSet() shouldBe setOf("key", "totals")
            stats["groups"][0]["totals"].propertyNames().toSet() shouldBe totalsKeys
        }
        expect("date-only bounds mean start of day") {
            val command = mockk<LibraryStatsCommand>()
            every { command.summarize(any()) } returns LibraryStats(empty, emptyList())
            val result = Stats(command).invoke("--recorded-from 2026-01-01 --recorded-to 2026-02-01 --json")
            result.statusCode shouldBe 0
            val stats = JsonMapper.builder().build().readTree(result.stdout)["stats"]
            stats["recordedFrom"].asString() shouldBe "2026-01-01T00:00:00"
            stats["recordedTo"].asString() shouldBe "2026-02-01T00:00:00"
            verify {
                command.summarize(
                    LibraryStatsQuery(null, LocalDateTime.parse("2026-01-01T00:00:00"), LocalDateTime.parse("2026-02-01T00:00:00")),
                )
            }
        }
        expect("human month output prints one block per group with unknown label") {
            val command = mockk<LibraryStatsCommand>()
            val month = empty.copy(programCount = 2, registeredBytes = 150)
            every { command.summarize(any()) } returns
                LibraryStats(month, listOf(LibraryStatsGroup(null, empty), LibraryStatsGroup("2026-01", month)))
            val result = Stats(command).invoke("--group-by month")
            result.statusCode shouldBe 0
            verify { command.summarize(LibraryStatsQuery(LibraryStatsQuery.Group.MONTH)) }
            val labels =
                result.stdout
                    .lines()
                    .filter { it.isNotEmpty() && !it.startsWith(" ") }
                    .drop(1)
                    .map { it.substringBefore(':') }
            labels shouldBe listOf("TOTAL", "(unknown)", "2026-01")
            result.stdout.contains("2026-01: programs=2") shouldBe true
        }
        expect("human output labels metadata and unverified physical usage") {
            val command = mockk<LibraryStatsCommand>()
            every { command.summarize(any()) } returns LibraryStats(empty, emptyList())
            val result = Stats(command).invoke("")
            result.statusCode shouldBe 0
            result.stdout.contains("programs=0") shouldBe true
            result.stdout.contains("physical usage: unverified") shouldBe true
        }
        expect("invalid options have exit two before database access") {
            listOf(
                "--group-by bad",
                "--recorded-from bad",
                "--recorded-from 2026-01-01T00:00:00Z",
                "--recorded-from 2026-13-01",
                "--recorded-to 2026-01-01t00:00",
                "--recorded-from 2026-02-01T00:00 --recorded-to 2026-01-01T00:00",
                "--ids-only",
                "--json --ids-only",
            ).forEach {
                Stats(mockk()).invoke(it).statusCode shouldBe 2
            }
        }
        // Exit code and secret-free stderr come from ReadCommand's shared error handling.
        expect("database failure gives sanitized stderr and no partial JSON") {
            val command = mockk<LibraryStatsCommand>()
            every { command.summarize(any()) } throws IllegalStateException("password=secret")
            val result = Stats(command).invoke("--json")
            result.statusCode shouldBe 1
            result.stdout shouldBe ""
            result.stderr.contains("secret") shouldBe false
        }
    })

private fun Stats.invoke(arguments: String) = test(arguments.split(' ').filter { it.isNotEmpty() }) { parseManager(it) }
