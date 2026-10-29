package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.library.api.CleanupPlanCommand
import me.pinfort.tsvideos.library.domain.BulkInterruptedException
import me.pinfort.tsvideos.library.domain.BulkOutcome
import me.pinfort.tsvideos.library.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.library.domain.BulkResult
import me.pinfort.tsvideos.library.domain.CleanupCriteria
import me.pinfort.tsvideos.library.domain.CleanupMode
import me.pinfort.tsvideos.library.domain.CleanupPlan
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
        expect("strict JSON round trips Unicode") {
            CleanupPlanJson.read(CleanupPlanJson.write(plan)) shouldBe plan
        }
        context("strict JSON rejects ambiguous or unsupported imports") {
            withData(
                mapOf<String, (String) -> String>(
                    "schemaVersion overflow" to { json: String -> json.replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297") },
                    "duplicate schemaVersion" to { json: String ->
                        json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")
                    },
                    "unknown property" to { json: String -> json.replace("\"action\":", "\"extra\":0,\"action\":") },
                    "fractional keep ID" to { json: String -> json.replace("\"keepIds\":[]", "\"keepIds\":[1.5]") },
                ),
            ) { corrupt ->
                val json = CleanupPlanJson.write(plan)
                val bad = corrupt(json)
                bad shouldNotBe json
                shouldThrowAny { CleanupPlanJson.read(bad) }
            }
        }
        context("plan without exactly one explicit mode is a usage error") {
            withData(
                nameFn = { it.joinToString(" ") },
                listOf("plan"),
                listOf("plan", "--duplicates", "--recorded-before", "2026-01-01T00:00:00"),
                listOf("plan", "--recorded-before", "2026-01-01T00:00:00", "--keep", "2"),
            ) { args ->
                val core = mockk<CleanupPlanCommand>()
                every { core.plan(any()) } returns plan
                Cleanup(core).testCleanup(args).statusCode shouldBe 2
            }
        }
        expect("plan preserves repeated keep IDs") {
            val core = mockk<CleanupPlanCommand>()
            every { core.plan(any()) } returns plan
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
                dry.stdout shouldContain "\"dryRun\":true"
                verify(exactly = 0) { core.apply(any(), false) }
                every { core.apply(plan, false) } returns BulkResult(listOf(BulkOutcome(2, BulkOutcomeStatus.STALE, "Changed")))
                val partial = Cleanup(core).testCleanup(listOf("apply", file.toString(), "--yes"))
                partial.statusCode shouldBe 4
                partial.stdout shouldContain "STALE"
                every { core.apply(plan, false) } returns BulkResult(listOf(BulkOutcome(2, BulkOutcomeStatus.NOT_FOUND, "Missing")))
                Cleanup(core).testCleanup(listOf("apply", file.toString(), "--yes")).statusCode shouldBe 3
                every { core.apply(plan, false) } returns
                    BulkResult(
                        listOf(BulkOutcome(2, BulkOutcomeStatus.NOT_FOUND, "Missing"), BulkOutcome(3, BulkOutcomeStatus.SUCCESS, "Done")),
                    )
                Cleanup(core).testCleanup(listOf("apply", file.toString(), "--yes")).statusCode shouldBe 4
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
        expect("malformed dates in criteria or an imported plan are usage errors, not crashes") {
            val core = mockk<CleanupPlanCommand>()
            every { core.plan(any()) } throws java.time.format.DateTimeParseException("bad date", "not-a-date", 0)
            Cleanup(core).testCleanup(listOf("plan", "--recorded-before", "not-a-date")).statusCode shouldBe 2
            val file = Files.createTempFile("cleanup-plan", ".json")
            try {
                Files.writeString(file, CleanupPlanJson.write(plan.copy(createdAt = "yesterday")))
                every { core.apply(any(), true) } throws java.time.format.DateTimeParseException("bad date", "yesterday", 0)
                Cleanup(core).testCleanup(listOf("apply", file.toString(), "--dry-run")).statusCode shouldBe 2
            } finally {
                Files.deleteIfExists(file)
            }
        }
        expect("interrupted apply still prints the outcomes completed before the interrupt") {
            val file = Files.createTempFile("cleanup-plan", ".json")
            val executor =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
            try {
                Files.writeString(file, CleanupPlanJson.write(plan))
                val core = mockk<CleanupPlanCommand>()
                every { core.apply(plan, false) } throws
                    BulkInterruptedException(listOf(BulkOutcome(2, BulkOutcomeStatus.SUCCESS, "Completed")), 3)
                val result =
                    executor
                        .submit<com.github.ajalt.clikt.testing.CliktCommandTestResult> {
                            try {
                                Cleanup(core).testCleanup(listOf("apply", file.toString(), "--yes"))
                            } finally {
                                Thread.interrupted()
                            }
                        }.get(10, java.util.concurrent.TimeUnit.SECONDS)
                result.statusCode shouldBe 130
                assertSoftly(result.stdout) {
                    shouldContain("\"interrupted\":true")
                    shouldContain("\"interruptedTargetId\":3")
                    shouldContain("\"status\":\"SUCCESS\"")
                }
            } finally {
                executor.shutdownNow()
                Files.deleteIfExists(file)
            }
        }
        expect("missing file and operational failures have stable exit codes without secrets") {
            val core = mockk<CleanupPlanCommand>()
            Cleanup(core).testCleanup(listOf("apply", "absent-cleanup-plan.json", "--dry-run")).statusCode shouldBe 3
            every { core.plan(any()) } throws IllegalStateException("password=secret")
            val failure = Cleanup(core).testCleanup(listOf("plan", "--duplicates"))
            failure.statusCode shouldBe 1
            failure.stdout shouldBe ""
            failure.stderr shouldNotContain "secret"
        }
    })

private fun Cleanup.testCleanup(arguments: List<String>) = test(arguments) { parseManager(it) }
