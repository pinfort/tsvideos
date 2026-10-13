package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.command.InventoryExportCommand
import me.pinfort.tsvideos.core.domain.InventoryExport
import me.pinfort.tsvideos.core.domain.InventoryProgram
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.manager.console.component.InventoryFormat
import me.pinfort.tsvideos.manager.console.parseManager
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDateTime

class ExportTest :
    ExpectSpec({
        val now = Instant.parse("2026-10-13T00:00:00Z")

        fun runExport(
            command: Export,
            args: List<String>,
        ) = command.test(args) { command.parseManager(it) }
        expect("empty JSON has an explicit version and inventory, CSV has headers") {
            val inventory = mockk<InventoryExportCommand>()
            every { inventory.export(any(), any()) } returns InventoryExport(now, emptyList(), emptyList())
            val json = runExport(Export(inventory), emptyList())
            json.statusCode shouldBe 0
            val node = JsonMapper.builder().build().readTree(json.stdout)
            node["schemaVersion"].asInt() shouldBe 1
            node["export"]["programs"].size() shouldBe 0
            node["export"]["generatedAt"].asString() shouldBe now.toString()
            runExport(Export(inventory), listOf("--format", "csv")).stdout.startsWith("\"schemaVersion\"") shouldBe true
        }
        expect("missing explicit IDs and operational errors never print partial inventory") {
            val inventory = mockk<InventoryExportCommand>()
            every { inventory.export(any(), any()) } returns InventoryExport(now, emptyList(), listOf(9))
            val missing = runExport(Export(inventory), listOf("--id", "9"))
            missing.statusCode shouldBe 3
            missing.stdout shouldBe ""
            every { inventory.export(any(), any()) } throws IllegalStateException("secret connection password")
            val failed = runExport(Export(inventory), emptyList())
            failed.statusCode shouldBe 1
            failed.stdout shouldBe ""
            failed.stderr.contains("secret") shouldBe false
        }
        expect("invalid and conflicting selection/format options fail before reads") {
            listOf(
                listOf("--id", "0"),
                listOf("--id", "1", "--channel", "BS1"),
                listOf("--ids-only"),
                listOf("--format", "csv", "--json"),
                listOf("--recorded-from", "invalid"),
                listOf("--min-drops", "5", "--max-drops", "1"),
            ).forEach { args ->
                runExport(Export(mockk()), args).statusCode shouldBe 2
            }
        }
        expect("CSV quotes Unicode multiline cells and neutralizes spreadsheet formulas") {
            val program = Program(1, "=日本語,\"引用\"\n二行目", 3, Program.Status.ERROR, -1, 0, LocalDateTime.MIN, "", "", "", 0.0)
            val csv =
                InventoryFormat.csv(
                    InventoryExport(now, listOf(InventoryProgram(program, null, emptyList(), emptyList())), emptyList()),
                )
            csv.contains("\"'=日本語,\"\"引用\"\"\n二行目\"") shouldBe true
            csv.contains("\"PROGRAM\"") shouldBe true
            csv.endsWith("\r\n") shouldBe true
        }
    })
