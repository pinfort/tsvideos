package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.console.common.component.UserQuestionComponent
import me.pinfort.tsvideos.processing.api.ProcessingHistoryCommand
import me.pinfort.tsvideos.processing.api.RecordingQualityCommand
import me.pinfort.tsvideos.processing.domain.ProcessingStatus
import me.pinfort.tsvideos.processing.domain.QualityAction
import me.pinfort.tsvideos.processing.domain.QualityReleaseRefusedException
import me.pinfort.tsvideos.processing.domain.RecordingQualityReport
import me.pinfort.tsvideos.processor.console.parseProcessor
import tools.jackson.databind.json.JsonMapper

class QualityCommandTest :
    ExpectSpec({
        val status = ProcessingStatus(3, "fingerprint", "/録画/番組.m2ts", "QUARANTINED", null, emptyList())

        fun history() = mockk<ProcessingHistoryCommand> { every { status(3) } returns listOf(status) }
        expect("report is typed UTF8 JSON and separates retained decision from current state") {
            val quality = mockk<RecordingQualityCommand>()
            every { quality.reports(3) } returns
                listOf(
                    RecordingQualityReport(
                        5,
                        3,
                        "2026-10-13T00:00:00Z",
                        QualityAction.QUARANTINE,
                        status.inputPath,
                        "fingerprint",
                        """[{"rule":"MAX_DROPPED_FRAMES","configured":"0","observed":"1","action":"QUARANTINE","reason":"THRESHOLD_EXCEEDED"}]""",
                    ),
                )
            val result = QualityReportCommand(quality, history()).qualityTest("--job-id 3 --json")
            result.statusCode shouldBe 0
            result.stderr shouldBe ""
            val root = JsonMapper.builder().build().readTree(result.stdout)
            root["schemaVersion"].asInt() shouldBe 1
            root["reports"][0]["quarantineLocation"].asString() shouldBe status.inputPath
            root["reports"][0]["findings"].isArray shouldBe true
            root["reports"][0]["findings"][0]["rule"].asString() shouldBe "MAX_DROPPED_FRAMES"
        }
        expect("known job without reports succeeds and unknown job returns three") {
            val quality = mockk<RecordingQualityCommand> { every { reports(3) } returns emptyList() }
            QualityReportCommand(quality, history()).qualityTest("--job-id 3 --json").statusCode shouldBe 0
            val missing = mockk<ProcessingHistoryCommand> { every { status(3) } returns emptyList() }
            QualityReportCommand(quality, missing).qualityTest("--job-id 3 --json").statusCode shouldBe 3
        }
        expect("malformed evidence or operational failure produces no partial JSON or secret") {
            val quality = mockk<RecordingQualityCommand>()
            every { quality.reports(3) } throws IllegalStateException("password=secret")
            val result = QualityReportCommand(quality, history()).qualityTest("--job-id 3 --json")
            result.statusCode shouldBe 1
            result.stdout shouldBe ""
            result.stderr shouldNotContain "secret"
        }
        context("invalid IDs are usage errors") {
            withData(nameFn = { "args='$it'" }, "--job-id 0", "--job-id -1", "--job-id nope", "") {
                QualityReportCommand(mockk(), mockk()).qualityTest(it).statusCode shouldBe 2
            }
        }
        expect("dry-run checks eligibility without prompt or mutation") {
            val quality = mockk<RecordingQualityCommand>(relaxed = true)
            val question = mockk<UserQuestionComponent>()
            QualityReleaseCommand(quality, history(), question).qualityTest("--job-id 3 --dry-run").statusCode shouldBe 0
            verify(exactly = 1) { quality.release(3, true) }
            verify(exactly = 0) {
                quality.release(3, false)
                question.askDefaultFalse(any())
            }
        }
        context("cancel preserves hold and confirmation revalidates before mutation") {
            withData(nameFn = { if (it) "confirmed" else "cancelled" }, false, true) { confirm ->
                val quality = mockk<RecordingQualityCommand>(relaxed = true)
                val question = mockk<UserQuestionComponent> { every { askDefaultFalse(any()) } returns confirm }
                QualityReleaseCommand(quality, history(), question).qualityTest("--job-id 3").statusCode shouldBe 0
                verify(exactly = 1) { quality.release(3, true) }
                verify(exactly = if (confirm) 1 else 0) { quality.release(3, false) }
            }
        }
        expect("yes bypasses only prompt and refusal has exit four with its reason") {
            val quality = mockk<RecordingQualityCommand>(relaxed = true)
            every { quality.release(3, false) } throws
                QualityReleaseRefusedException(QualityReleaseRefusedException.Reason.CHANGED_CONCURRENTLY)
            val question = mockk<UserQuestionComponent>()
            val result = QualityReleaseCommand(quality, history(), question).qualityTest("--job-id 3 --yes")
            result.statusCode shouldBe 4
            result.stderr shouldContain "CHANGED_CONCURRENTLY"
            verify(exactly = 1) {
                quality.release(3, true)
                quality.release(3, false)
            }
            verify(exactly = 0) { question.askDefaultFalse(any()) }
        }
        expect("an unexpected release failure is an operational error and stays generic") {
            val quality = mockk<RecordingQualityCommand>(relaxed = true)
            every { quality.release(3, true) } throws IllegalStateException("password=secret")
            val result = QualityReleaseCommand(quality, history(), mockk()).qualityTest("--job-id 3 --yes")
            result.statusCode shouldBe 1
            result.stderr shouldNotContain "secret"
        }
    })

private fun CliktCommand.qualityTest(args: String) = test(args.split(' ').filter { it.isNotEmpty() }) { parseProcessor(it) }
