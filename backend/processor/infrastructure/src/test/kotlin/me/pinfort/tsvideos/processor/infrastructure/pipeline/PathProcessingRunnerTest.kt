package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.processor.infrastructure.external.slack.SlackClient
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files

class PathProcessingRunnerTest :
    ExpectSpec({
        lateinit var fileProcessingPipeline: FileProcessingPipeline
        lateinit var slackClient: SlackClient
        lateinit var logger: Logger
        lateinit var pathProcessingRunner: PathProcessingRunner

        beforeTest {
            clearAllMocks()
            fileProcessingPipeline = mockk()
            slackClient = mockk()
            logger = mockk(relaxed = true)
            pathProcessingRunner = PathProcessingRunner(fileProcessingPipeline, slackClient, logger)
        }

        expect("batch continues through quarantine and failure and returns exact counts") {
            val dir = Files.createTempDirectory("quality-batch").toFile()
            try {
                (1..4).forEach { File(dir, "$it.m2ts").writeText("recording") }
                every { slackClient.notify(any()) } just Runs
                every { fileProcessingPipeline.processFile(any(), any(), any(), any(), any(), any()) } answers {
                    when (firstArg<File>().name) {
                        "1.m2ts" -> FileProcessingPipeline.Result.QUARANTINED
                        "2.m2ts" -> throw IllegalStateException("failure")
                        "3.m2ts" -> FileProcessingPipeline.Result.PROCESSED
                        else -> FileProcessingPipeline.Result.SKIPPED_ALREADY_REGISTERED
                    }
                }
                pathProcessingRunner.processPath(dir.toPath()) shouldBe PathProcessingRunner.Summary(1, 1, 1, 1)
                verify(exactly = 1) { slackClient.notify(any()) }
            } finally {
                dir.deleteRecursively()
            }
        }

        expect("interruption propagates and prevents admission of subsequent recordings") {
            val dir = Files.createTempDirectory("interrupt-path").toFile()
            val first = File(dir, "a.m2ts").apply { writeText("a") }
            val second = File(dir, "b.m2ts").apply { writeText("b") }
            every { fileProcessingPipeline.processFile(any(), any(), any(), any(), any(), any()) } throws InterruptedException()
            try {
                shouldThrow<InterruptedException> { pathProcessingRunner.processPath(dir.toPath()) }
                verify(exactly = 1) { fileProcessingPipeline.processFile(first, any(), any(), any(), any(), any()) }
                verify(exactly = 0) { fileProcessingPipeline.processFile(second, any(), any(), any(), any(), any()) }
                verify(exactly = 0) { slackClient.notify(any()) }
            } finally {
                first.delete()
                second.delete()
                dir.delete()
            }
        }
        context("processPath") {
            expect("notifies via Slack and does nothing when the path does not exist") {
                every { slackClient.notify(any()) } just Runs

                pathProcessingRunner.processPath(File("/nonexistent/path").toPath())

                verify { slackClient.notify(any()) }
                verify(exactly = 0) { fileProcessingPipeline.processFile(any(), any(), any(), any(), any(), any()) }
            }

            expect("processes a single file path") {
                val file = File.createTempFile("process-path-test", ".m2ts")
                every { fileProcessingPipeline.processFile(any(), any(), any(), any(), any(), any()) } returns
                    FileProcessingPipeline.Result.PROCESSED

                pathProcessingRunner.processPath(file.toPath())

                verify { fileProcessingPipeline.processFile(file, false, any(), any(), any(), any()) }
            }

            expect("processes every .m2ts file in a directory, ignoring other extensions") {
                val dir = Files.createTempDirectory("process-path-test").toFile()
                val m2ts1 = File(dir, "a.m2ts").apply { writeBytes(byteArrayOf(1)) }
                val m2ts2 = File(dir, "b.m2ts").apply { writeBytes(byteArrayOf(1)) }
                File(dir, "c.txt").writeBytes(byteArrayOf(1))
                every { fileProcessingPipeline.processFile(any(), any(), any(), any(), any(), any()) } returns
                    FileProcessingPipeline.Result.PROCESSED

                pathProcessingRunner.processPath(dir.toPath())

                verify { fileProcessingPipeline.processFile(m2ts1, false, any(), any(), any(), any()) }
                verify { fileProcessingPipeline.processFile(m2ts2, false, any(), any(), any(), any()) }
                verify(exactly = 2) { fileProcessingPipeline.processFile(any(), any(), any(), any(), any(), any()) }
            }

            expect("notifies via Slack and continues to the next file when processing fails") {
                val dir = Files.createTempDirectory("process-path-test").toFile()
                val m2ts1 = File(dir, "a.m2ts").apply { writeBytes(byteArrayOf(1)) }
                val m2ts2 = File(dir, "b.m2ts").apply { writeBytes(byteArrayOf(1)) }
                every { fileProcessingPipeline.processFile(m2ts1, false, any(), any(), any(), any()) } throws RuntimeException("boom")
                every { fileProcessingPipeline.processFile(m2ts2, false, any(), any(), any(), any()) } returns
                    FileProcessingPipeline.Result.PROCESSED
                every { slackClient.notify(any()) } just Runs

                pathProcessingRunner.processPath(dir.toPath())

                verify { slackClient.notify(any()) }
                verify { fileProcessingPipeline.processFile(m2ts2, false, any(), any(), any(), any()) }
            }

            expect("threads dryRun through to processFile") {
                val file = File.createTempFile("process-path-test", ".m2ts")
                every { fileProcessingPipeline.processFile(any(), any(), any(), any(), any(), any()) } returns
                    FileProcessingPipeline.Result.PROCESSED

                pathProcessingRunner.processPath(file.toPath(), dryRun = true)

                verify { fileProcessingPipeline.processFile(file, true, any(), any(), any(), any()) }
            }
        }
    })
