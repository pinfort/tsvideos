package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.command.CleanupPlanCommand
import me.pinfort.tsvideos.core.domain.BulkOutcome
import me.pinfort.tsvideos.core.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.core.domain.BulkResult
import me.pinfort.tsvideos.core.domain.CleanupCriteria
import me.pinfort.tsvideos.core.domain.CleanupMode
import me.pinfort.tsvideos.core.domain.CleanupPlan
import me.pinfort.tsvideos.manager.console.component.CleanupPlanJson
import me.pinfort.tsvideos.manager.console.parseManager
import java.nio.file.Files

class CleanupTest :
    ExpectSpec({
        val plan =
            CleanupPlan(
                createdAt = "2026-10-13T00:00:00Z",
                criteria = CleanupCriteria(CleanupMode.DUPLICATES, channel = "日本語"),
                groups = emptyList(),
                targets = emptyList(),
                keptTargets = emptyList(),
            )
        expect("strict JSON round trips Unicode and rejects ambiguous or unsupported imports") {
            val json = CleanupPlanJson.write(plan)
            CleanupPlanJson.read(json) shouldBe plan
            for (bad in listOf(
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297"),
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                json.replace("\"action\":", "\"extra\":0,\"action\":"),
                json.replace("\"keepIds\":[]", "\"keepIds\":[1.5]"),
            )) {
                shouldThrowAny { CleanupPlanJson.read(bad) }
            }
        }
        expect("plan requires one explicit mode and preserves repeated keep IDs") {
            val core = mockk<CleanupPlanCommand>()
            every { core.plan(any()) } returns plan
            for (args in listOf(listOf("plan"), listOf("plan", "--duplicates", "--recorded-before", "2026-01-01T00:00:00"))) {
                Cleanup(core).testCleanup(args).statusCode shouldBe 2
            }
            val result = Cleanup(core).testCleanup(listOf("plan", "--duplicates", "--keep", "2", "--keep", "4"))
            result.statusCode shouldBe 0
            CleanupPlanJson.read(result.stdout) shouldBe plan
            verify { core.plan(CleanupCriteria(CleanupMode.DUPLICATES, keepIds = listOf(2, 4))) }
        }
        expect("apply requires explicit confirmation while dry run remains nonmutating") {
            val file = Files.createTempFile("cleanup-plan", ".json")
            try {
                Files.writeString(file, CleanupPlanJson.write(plan))
                val core = mockk<CleanupPlanCommand>()
                every { core.apply(plan, true) } returns BulkResult(emptyList())
                Cleanup(core).testCleanup(listOf("apply", file.toString())).statusCode shouldBe 2
                val dry = Cleanup(core).testCleanup(listOf("apply", file.toString(), "--dry-run"))
                dry.statusCode shouldBe 0
                dry.stdout.contains("\"dryRun\":true") shouldBe true
                verify(exactly = 0) { core.apply(any(), false) }
                every { core.apply(plan, false) } returns BulkResult(listOf(BulkOutcome(2, BulkOutcomeStatus.STALE, "Changed")))
                val partial = Cleanup(core).testCleanup(listOf("apply", file.toString(), "--yes"))
                partial.statusCode shouldBe 4
                partial.stdout.contains("STALE") shouldBe true
                every { core.apply(plan, false) } returns BulkResult(listOf(BulkOutcome(2, BulkOutcomeStatus.NOT_FOUND, "Missing")))
                Cleanup(core).testCleanup(listOf("apply", file.toString(), "--yes")).statusCode shouldBe 3
                Files.writeString(file, "{}")
                Cleanup(core).testCleanup(listOf("apply", file.toString(), "--dry-run")).statusCode shouldBe 2
            } finally {
                Files.deleteIfExists(file)
            }
        }
        expect("interrupted cleanup exits 130 and preserves the interrupt flag") {
            val core = mockk<CleanupPlanCommand>()
            every { core.plan(any()) } throws InterruptedException("cancel")
            val executor =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
            try {
                val result =
                    executor
                        .submit<Pair<Int, Boolean>> {
                            try {
                                val result = Cleanup(core).testCleanup(listOf("plan", "--duplicates"))
                                result.statusCode to Thread.currentThread().isInterrupted
                            } finally {
                                Thread.interrupted()
                            }
                        }.get(10, java.util.concurrent.TimeUnit.SECONDS)
                result shouldBe (130 to true)
            } finally {
                executor.shutdownNow()
            }
        }
        expect("missing file and operational failures have stable exit codes without secrets") {
            val core = mockk<CleanupPlanCommand>()
            Cleanup(core).testCleanup(listOf("apply", "absent-cleanup-plan.json", "--dry-run")).statusCode shouldBe 3
            every { core.plan(any()) } throws IllegalStateException("password=secret")
            val failure = Cleanup(core).testCleanup(listOf("plan", "--duplicates"))
            failure.statusCode shouldBe 1
            failure.stdout shouldBe ""
            failure.stderr.contains("secret") shouldBe false
        }
    })

private fun Cleanup.testCleanup(arguments: List<String>) = test(arguments) { parseManager(it) }
