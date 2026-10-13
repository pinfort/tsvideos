package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.component.ProcessingStageTimings
import me.pinfort.tsvideos.core.domain.ProcessingStage
import me.pinfort.tsvideos.processor.infrastructure.external.slack.SlackClient
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BatchReportingTest :
    ExpectSpec({
        expect("summary interruption preserves completed outcomes and returns interrupted exit") {
            for (throws in listOf(false, true)) {
                val root = Files.createTempDirectory("batch-notification-interrupt").toFile()
                val slack = mockk<SlackClient>()
                every { slack.notify(any()) } answers {
                    if (throws) throw InterruptedException("notification interrupted")
                    Thread.currentThread().interrupt()
                }
                val result = AtomicReference<ProcessingBatchReport>()
                val caller =
                    Thread {
                        result.set(
                            PathProcessingRunner(mockk(), slack, mockk(relaxed = true))
                                .processReport(listOf(root.toPath()), notifications = BatchNotification.SUMMARY),
                        )
                    }
                try {
                    caller.start()
                    caller.join(5000)
                    caller.isAlive shouldBe false
                    result.get().interrupted shouldBe true
                    result.get().exitCode shouldBe 130
                    result.get().recordings shouldBe emptyList()
                } finally {
                    root.deleteRecursively()
                }
            }
        }
        expect("none suppresses failures and unavailable summary does not change processing results") {
            val root = Files.createTempDirectory("batch-notification-policy").toFile()
            val input = root.resolve("input.m2ts").apply { writeText("input") }
            val pipeline = mockk<FileProcessingPipeline>()
            val slack = mockk<SlackClient>()
            every { pipeline.processFile(any(), any(), any(), any(), any()) } throws IllegalArgumentException("secret")
            every { slack.notify(any()) } throws IllegalStateException("secret")
            try {
                val none =
                    PathProcessingRunner(pipeline, slack, mockk(relaxed = true))
                        .processReport(listOf(input.toPath()), notifications = BatchNotification.NONE)
                none.failed shouldBe 1
                none.exitCode shouldBe 1
                verify(exactly = 0) { slack.notify(any()) }
                every { pipeline.processFile(any(), any(), any(), any(), any()) } returns FileProcessingPipeline.Result.PROCESSED
                val summary =
                    PathProcessingRunner(pipeline, slack, mockk(relaxed = true))
                        .processReport(listOf(input.toPath()), notifications = BatchNotification.SUMMARY)
                summary.submitted shouldBe 1
                summary.exitCode shouldBe 0
                summary.interrupted shouldBe false
                verify(exactly = 1) { slack.notify(any()) }
            } finally {
                root.deleteRecursively()
            }
        }
        expect("parallel results retain input order individual stages and one summary notification") {
            val root = Files.createTempDirectory("batch-report").toFile()
            val files = (1..3).map { root.resolve("$it.m2ts").apply { writeText("input") } }
            val timer = ProcessingStageTimings()
            val pipeline = mockk<FileProcessingPipeline>()
            val slack = mockk<SlackClient>(relaxed = true)
            val secondDone = CountDownLatch(1)
            every { pipeline.processFile(any(), any(), any(), any(), any()) } answers {
                val file = firstArg<java.io.File>()
                timer.measure(if (file.name == "1.m2ts") ProcessingStage.INSPECT else ProcessingStage.SPLIT) {
                    when (file.name) {
                        "1.m2ts" -> {
                            check(secondDone.await(5, TimeUnit.SECONDS))
                            FileProcessingPipeline.Result.PROCESSED
                        }
                        "2.m2ts" -> {
                            secondDone.countDown()
                            error("password=never-print")
                        }
                        else -> FileProcessingPipeline.Result.SKIPPED_ALREADY_REGISTERED
                    }
                }
            }
            try {
                val runner =
                    PathProcessingRunner(
                        pipeline,
                        slack,
                        mockk(relaxed = true),
                        ProcessingCancellation(),
                        ProcessingConcurrency(workers = 2),
                        timer,
                    )
                val report = runner.processReport(files.map { it.toPath() }, notifications = BatchNotification.SUMMARY)
                report.recordings.map { it.path } shouldBe files.map { it.canonicalPath }
                report.recordings.map { it.outcome } shouldBe
                    listOf(BatchOutcome.SUBMITTED, BatchOutcome.FAILED, BatchOutcome.SKIPPED_ALREADY_REGISTERED)
                report.recordings.map { it.stageTimings.single().stage } shouldBe
                    listOf(ProcessingStage.INSPECT, ProcessingStage.SPLIT, ProcessingStage.SPLIT)
                report.exitCode shouldBe 4
                report.toString().contains("never-print") shouldBe false
                verify(exactly = 1) { slack.notify(match { it.startsWith("Processing batch:") }) }
            } finally {
                root.deleteRecursively()
            }
        }
        expect("empty and missing inputs differ and dryrun suppresses all Slack modes") {
            val root = Files.createTempDirectory("batch-empty").toFile()
            val slack = mockk<SlackClient>(relaxed = true)
            try {
                PathProcessingRunner(mockk(), slack, mockk(relaxed = true)).processReport(listOf(root.toPath())).exitCode shouldBe 0
                for (mode in BatchNotification.entries) {
                    val report =
                        PathProcessingRunner(
                            mockk(),
                            slack,
                            mockk(relaxed = true),
                        ).processReport(listOf(root.resolve("missing").toPath()), true, mode)
                    report.exitCode shouldBe 1
                    report.recordings.single().reasonCode shouldBe "INPUT_MISSING"
                }
                verify(exactly = 0) { slack.notify(any()) }
            } finally {
                root.deleteRecursively()
            }
        }
        expect("interrupted batch retains settled worker and queued outcomes without success") {
            val root = Files.createTempDirectory("batch-interrupt").toFile()
            repeat(8) { root.resolve("$it.m2ts").writeText("input") }
            val cancellation = ProcessingCancellation()
            val started = CountDownLatch(1)
            val pipeline = mockk<FileProcessingPipeline>()
            every { pipeline.processFile(any(), any(), any(), any(), any()) } answers {
                started.countDown()
                CountDownLatch(1).await()
                FileProcessingPipeline.Result.PROCESSED
            }
            val runner =
                PathProcessingRunner(
                    pipeline,
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    cancellation,
                    ProcessingConcurrency(workers = 1, queueCapacity = 1),
                    graceSeconds = 2,
                )
            val result = AtomicReference<ProcessingBatchReport>()
            val producer = Thread { result.set(runner.processReport(listOf(root.toPath()))) }.apply { start() }
            try {
                started.await(5, TimeUnit.SECONDS) shouldBe true
                cancellation.requestStop()
                producer.join(5000)
                producer.isAlive shouldBe false
                val report = checkNotNull(result.get())
                report.exitCode shouldBe 130
                report.submitted shouldBe 0
                report.interruptedRecordings shouldBe 1
                report.completeDiscovery shouldBe false
                report.recordings.all { it.outcome in setOf(BatchOutcome.INTERRUPTED, BatchOutcome.NOT_STARTED) } shouldBe true
            } finally {
                cancellation.requestStop()
                producer.join(5000)
                root.deleteRecursively()
            }
        }
    })
