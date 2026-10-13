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
                "--recorded-from 2026-02-01T00:00 --recorded-to 2026-01-01T00:00",
                "--ids-only",
                "--json --ids-only",
            ).forEach {
                Stats(mockk()).invoke(it).statusCode shouldBe 2
            }
        }
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
