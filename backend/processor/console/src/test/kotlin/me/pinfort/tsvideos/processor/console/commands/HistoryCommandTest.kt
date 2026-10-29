package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.processing.api.ProcessingHistoryCommand
import me.pinfort.tsvideos.processing.domain.ProcessingAttempt
import me.pinfort.tsvideos.processing.domain.ProcessingAttemptHistory
import me.pinfort.tsvideos.processing.domain.ProcessingAttemptKind
import me.pinfort.tsvideos.processing.domain.ProcessingHistoryIdentity
import me.pinfort.tsvideos.processing.domain.ProcessingOutcome
import me.pinfort.tsvideos.processing.domain.ProcessingStatus
import me.pinfort.tsvideos.processor.console.parseProcessor
import tools.jackson.databind.json.JsonMapper

class HistoryCommandTest :
    ExpectSpec({
        val json = JsonMapper.builder().build()
        val attempt =
            ProcessingAttempt(
                17,
                3,
                2,
                ProcessingAttemptKind.PROCESS,
                ProcessingOutcome.COMPLETED,
                "2026-10-13T01:02:03.123456Z",
                "2026-10-13T01:03:03.123456Z",
                "2026-10-13T01:03:03.123456Z",
                null,
            )
        val status = ProcessingStatus(3, "key", "/録画/番組\"\n名前", "SUBMITTED", attempt, emptyList())
        expect("status JSON separates completed invocation from waiting encoder job and preserves scalar types") {
            val history = mockk<ProcessingHistoryCommand>()
            every { history.status(3) } returns listOf(status)
            val result = StatusCommand(history).readTest("--job-id 3 --json")
            result.statusCode shouldBe 0
            result.stderr shouldBe ""
            val root = json.readTree(result.stdout)
            root["schemaVersion"].asInt() shouldBe 1
            root["jobs"][0]["jobId"].asLong() shouldBe 3
            root["jobs"][0]["inputPath"].asString() shouldBe status.inputPath
            root["jobs"][0]["jobState"].asString() shouldBe "SUBMITTED"
            root["jobs"][0]["latestAttempt"]["outcome"].asString() shouldBe "COMPLETED"
            root["jobs"][0]["latestAttempt"]["activityAt"].asString() shouldBe attempt.activityAt
            root["jobs"][0]["activeAttempts"].size() shouldBe 0
        }
        expect("retained history emits immutable identity even when live job is gone") {
            val history = mockk<ProcessingHistoryCommand>()
            every { history.status(3) } returns listOf(status.copy(jobState = "RETIRED"))
            every { history.history(3) } returns
                listOf(
                    ProcessingAttemptHistory(
                        ProcessingHistoryIdentity(3, "key", status.inputPath, 9007199254740993, 100, 2),
                        attempt,
                        emptyList(),
                    ),
                )
            val result = HistoryCommand(history).readTest("--job-id 3 --json")
            result.statusCode shouldBe 0
            val record = json.readTree(result.stdout)["attempts"][0]
            record["identity"]["inputSize"].asLong() shouldBe 9007199254740993
            record["attempt"]["attemptNumber"].asInt() shouldBe 2
            record["stages"].size() shouldBe 0
        }
        expect("empty all lists and known legacy jobs without attempts succeed") {
            val history = mockk<ProcessingHistoryCommand>()
            every { history.status(null) } returns emptyList()
            every { history.history(null) } returns emptyList()
            every { history.status(3) } returns listOf(status.copy(latestAttempt = null))
            every { history.history(3) } returns emptyList()
            StatusCommand(history).readTest("--all --json").statusCode shouldBe 0
            json.readTree(HistoryCommand(history).readTest("--all --json").stdout)["attempts"].size() shouldBe 0
            HistoryCommand(history).readTest("--job-id 3 --json").statusCode shouldBe 0
        }
        context("missing requested job returns 3 without partial stdout") {
            withData(
                mapOf<String, (ProcessingHistoryCommand) -> HistoryReadCommand>(
                    "status" to ::StatusCommand,
                    "history" to ::HistoryCommand,
                ),
            ) { newCommand ->
                val history = mockk<ProcessingHistoryCommand>()
                every { history.status(9) } returns emptyList()
                val result = newCommand(history).readTest("--job-id 9 --json")
                result.statusCode shouldBe 3
                result.stdout shouldBe ""
            }
        }
        context("invalid and conflicting selectors return 2") {
            withData(nameFn = { "args='$it'" }, "--job-id 0", "--job-id nope", "--job-id -1", "--job-id 3 --all") {
                StatusCommand(mockk()).readTest(it).statusCode shouldBe 2
            }
        }
        expect("driver failure exposes no secrets or partial JSON") {
            val history = mockk<ProcessingHistoryCommand>()
            every { history.status(null) } throws IllegalStateException("password=secret-value jdbc://sensitive")
            val result = StatusCommand(history).readTest("--json")
            result.statusCode shouldBe 1
            result.stdout shouldBe ""
            result.stderr shouldNotContain "secret-value"
            result.stderr shouldNotContain "sensitive"
        }
    })

private fun HistoryReadCommand.readTest(args: String) = test(args.split(' ')) { parseProcessor(it) }
