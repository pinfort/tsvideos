package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.ProgramSearchPage
import me.pinfort.tsvideos.catalog.domain.ProgramSearchQuery
import me.pinfort.tsvideos.manager.console.parseManager
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime

class SearchFiltersTest :
    ExpectSpec({
        expect("combined CLI filters map to the reusable query and JSON includes paging metadata") {
            val programs = mockk<ProgramCommand>()
            val query = slot<ProgramSearchQuery>()
            every { programs.search(capture(query)) } returns ProgramSearchPage(emptyList(), 50, 10, 20)
            val command = Search(programs, mockk())
            val args =
                listOf(
                    "--channel",
                    "BS1",
                    "--recorded-from",
                    "2026-10-01T00:00:00",
                    "--recorded-to",
                    "2026-11-01T00:00:00",
                    "--status",
                    "completed",
                    "--min-drops",
                    "0",
                    "--max-drops",
                    "5",
                    "--file-type",
                    "encoded",
                    "--sort",
                    "size",
                    "--direction",
                    "asc",
                    "--limit",
                    "10",
                    "--offset",
                    "20",
                    "--json",
                )
            val result = command.test(args) { command.parseManager(it) }
            result.statusCode shouldBe 0
            result.stderr shouldBe ""
            query.captured shouldBe
                ProgramSearchQuery(
                    channel = "BS1",
                    recordedFrom = LocalDateTime.parse("2026-10-01T00:00:00"),
                    recordedTo = LocalDateTime.parse("2026-11-01T00:00:00"),
                    status = Program.Status.COMPLETED,
                    minDrops = 0,
                    maxDrops = 5,
                    fileType = ProgramSearchQuery.FileType.ENCODED,
                    sort = ProgramSearchQuery.Sort.SIZE,
                    direction = ProgramSearchQuery.Direction.ASC,
                    limit = 10,
                    offset = 20,
                )
            val root = JsonMapper.builder().build().readTree(result.stdout)
            root["programs"].size() shouldBe 0
            root["total"].asLong() shouldBe 50
            root["limit"].asInt() shouldBe 10
            root["offset"].asInt() shouldBe 20
        }

        expect("search without name uses documented core defaults") {
            val programs = mockk<ProgramCommand>()
            every { programs.search(ProgramSearchQuery()) } returns ProgramSearchPage(emptyList(), 0, 100, 0)
            val command = Search(programs, mockk())
            val result = command.test(listOf("--ids-only")) { command.parseManager(it) }
            result.statusCode shouldBe 0
            result.stdout shouldBe ""
            result.stderr shouldBe ""
        }

        expect("plain dates mean midnight of that day") {
            val programs = mockk<ProgramCommand>()
            val query =
                ProgramSearchQuery(
                    recordedFrom = LocalDateTime.parse("2026-10-01T00:00:00"),
                    recordedTo = LocalDateTime.parse("2026-10-02T00:00:00"),
                )
            every { programs.search(query) } returns ProgramSearchPage(emptyList(), 0, 100, 0)
            val command = Search(programs, mockk())
            val result =
                command.test(listOf("--recorded-from", "2026-10-01", "--recorded-to", "2026-10-02", "--ids-only")) {
                    command.parseManager(it)
                }
            result.statusCode shouldBe 0
        }

        context("invalid filters fail before DB calls with exit two and no stdout") {
            withData(
                nameFn = { "options='$it'" },
                "--limit 0",
                "--limit 501",
                "--offset -1",
                "--min-drops -1",
                "--max-drops -1",
                "--min-drops 3 --max-drops 2",
                "--recorded-from bad",
                "--recorded-from 2026-10-12T00:00:00 --recorded-to 2026-10-12T00:00:00",
                "--status missing",
                "--file-type jpg",
                "--sort name",
                "--direction sideways",
            ) { options ->
                val command = Search(mockk(), mockk())
                val result = command.test(("$options --json").split(' ')) { command.parseManager(it) }
                result.statusCode shouldBe 2
                result.stdout shouldBe ""
                result.stderr.shouldNotBeBlank()
            }
        }
    })
