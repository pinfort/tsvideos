package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.processor.console.parseProcessor
import me.pinfort.tsvideos.processor.infrastructure.pipeline.BatchNotification
import me.pinfort.tsvideos.processor.infrastructure.pipeline.BatchOutcome
import me.pinfort.tsvideos.processor.infrastructure.pipeline.PathProcessingRunner
import me.pinfort.tsvideos.processor.infrastructure.pipeline.ProcessingBatchReport
import me.pinfort.tsvideos.processor.infrastructure.pipeline.RecordingReport
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path

internal fun batchFixture(
    success: Int = 0,
    failed: Int = 0,
    interrupted: Boolean = false,
) = ProcessingBatchReport(
    "2026-01-01T00:00:00Z",
    "2026-01-01T00:00:01Z",
    false,
    interrupted,
    true,
    (0 until success + failed).map { index ->
        RecordingReport(
            index,
            "日本語-$index",
            if (index <
                success
            ) {
                BatchOutcome.SUBMITTED
            } else {
                BatchOutcome.FAILED
            },
            if (index < success) "SUBMITTED" else "PROCESSING_FAILED",
            1,
            emptyList(),
        )
    },
)

class ProcessCommandTest :
    ExpectSpec({
        expect("JSON is a single document with progress only on stderr and partial failure exit four") {
            val runner = mockk<PathProcessingRunner>()
            every { runner.processReport(any(), any(), any(), any(), any()) } answers {
                arg<(PathProcessingRunner.Progress) -> Unit>(3)(PathProcessingRunner.Progress("日本語", "INSPECT", 1, 2))
                batchFixture(1, 1)
            }
            val result = ProcessCommand(runner).reportTest(listOf("--json", "/path"))
            result.statusCode shouldBe 4
            val json = JsonMapper.builder().build().readTree(result.stdout)
            json["schemaVersion"].asInt() shouldBe 1
            json["batch"]["recordings"][0]["path"].asString() shouldBe "日本語-0"
            json["batch"]["failed"].asInt() shouldBe 1
            result.stdout.contains("INSPECT 50%") shouldBe false
            result.stderr.contains("INSPECT 50%") shouldBe true
        }
        expect("all failed and interrupted reports remain JSON with meaningful exit status") {
            for ((report, code) in listOf(batchFixture(0, 1) to 1, batchFixture(interrupted = true) to 130)) {
                val runner = mockk<PathProcessingRunner>()
                every { runner.processReport(any(), any(), any(), any(), any()) } returns report
                val result = ProcessCommand(runner).reportTest(listOf("--json", "/path"))
                result.statusCode shouldBe code
                JsonMapper
                    .builder()
                    .build()
                    .readTree(result.stdout)["batch"]["exitCode"]
                    .asInt() shouldBe code
            }
        }
        expect("all inputs dryrun and explicit summary policy reach infrastructure") {
            val runner = mockk<PathProcessingRunner>()
            every { runner.processReport(any(), any(), any(), any(), any()) } returns batchFixture()
            val result = ProcessCommand(runner).reportTest(listOf("--dry-run", "--slack", "summary", "/one", "/two"))
            result.statusCode shouldBe 0
            verify { runner.processReport(listOf(Path.of("/one"), Path.of("/two")), true, BatchNotification.SUMMARY, any(), any()) }
        }
        expect("invalid notification mode rejected before processing") {
            val result = ProcessCommand(mockk()).reportTest(listOf("--slack", "bad", "/path"))
            result.statusCode shouldBe 2
        }
    })

private fun ProcessCommand.reportTest(args: List<String>) = test(args) { parseProcessor(it) }
