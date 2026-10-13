package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.command.RecoveryInspectionCommand
import me.pinfort.tsvideos.core.domain.RecoveryInspection
import me.pinfort.tsvideos.core.domain.ResumeJobMissing
import me.pinfort.tsvideos.processor.console.parseProcessor
import tools.jackson.databind.json.JsonMapper

class RecoveryInspectionCliTest :
    ExpectSpec({
        expect("recover requires dry-run and one selector before calling core") {
            val core = mockk<RecoveryInspectionCommand>()
            RecoverCommand(core).invoke(listOf("--job-id", "1")).statusCode shouldBe 2
            RecoverCommand(core).invoke(listOf("--dry-run")).statusCode shouldBe 2
            InspectCommand(core).invoke(listOf("--job-id", "1", "--recording", "a")).statusCode shouldBe 2
            verify(exactly = 0) { core.inspect(any(), any()) }
        }
        expect("strict JSON and text show safe planning with waiting distinct from recover eligibility") {
            val core = mockk<RecoveryInspectionCommand>()
            every { core.inspect(1, null) } returns RecoveryInspection("日本語.m2ts", listOf(1), "SUBMITTED", "WAITING")
            val inspected = InspectCommand(core).invoke(listOf("--job-id", "1", "--json"))
            inspected.statusCode shouldBe 0
            JsonMapper
                .builder()
                .build()
                .readTree(inspected.stdout)["inspection"]["status"]
                .asString() shouldBe "WAITING"
            val planned = RecoverCommand(core).invoke(listOf("--job-id", "1", "--dry-run", "--json"))
            planned.statusCode shouldBe 4
            JsonMapper
                .builder()
                .build()
                .readTree(planned.stdout)["dryRun"]
                .asBoolean() shouldBe true
            InspectCommand(core).invoke(listOf("--job-id", "1")).stdout.contains("日本語") shouldBe true
        }
        expect("unknown operational and interrupt failures retain safe exit codes") {
            val core = mockk<RecoveryInspectionCommand>()
            every { core.inspect(1, null) } throws ResumeJobMissing()
            InspectCommand(core).invoke(listOf("--job-id", "1")).statusCode shouldBe 3
            every { core.inspect(1, null) } throws java.io.IOException("secret")
            val failed = InspectCommand(core).invoke(listOf("--job-id", "1"))
            failed.statusCode shouldBe 1
            failed.stderr.contains("secret") shouldBe false
            every { core.inspect(1, null) } throws InterruptedException()
            InspectCommand(core).invoke(listOf("--job-id", "1")).statusCode shouldBe 130
        }
    })

private fun RecoveryInspectionCli.invoke(args: List<String>) = test(args) { parseProcessor(it) }
