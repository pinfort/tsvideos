package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.core.spec.style.ExpectSpec
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

        context("processPath") {
            expect("notifies via Slack and does nothing when the path does not exist") {
                every { slackClient.notify(any()) } just Runs

                pathProcessingRunner.processPath(File("/nonexistent/path").toPath())

                verify { slackClient.notify(any()) }
                verify(exactly = 0) { fileProcessingPipeline.processFile(any(), any(), any(), any(), any()) }
            }

            expect("processes a single file path") {
                val file = File.createTempFile("process-path-test", ".m2ts")
                every { fileProcessingPipeline.processFile(any(), any(), any(), any(), any()) } returns
                    FileProcessingPipeline.Result.PROCESSED

                pathProcessingRunner.processPath(file.toPath())

                verify { fileProcessingPipeline.processFile(file, false, any(), any(), any()) }
            }

            expect("processes every .m2ts file in a directory, ignoring other extensions") {
                val dir = Files.createTempDirectory("process-path-test").toFile()
                val m2ts1 = File(dir, "a.m2ts").apply { writeBytes(byteArrayOf(1)) }
                val m2ts2 = File(dir, "b.m2ts").apply { writeBytes(byteArrayOf(1)) }
                File(dir, "c.txt").writeBytes(byteArrayOf(1))
                every { fileProcessingPipeline.processFile(any(), any(), any(), any(), any()) } returns
                    FileProcessingPipeline.Result.PROCESSED

                pathProcessingRunner.processPath(dir.toPath())

                verify { fileProcessingPipeline.processFile(m2ts1, false, any(), any(), any()) }
                verify { fileProcessingPipeline.processFile(m2ts2, false, any(), any(), any()) }
                verify(exactly = 2) { fileProcessingPipeline.processFile(any(), any(), any(), any(), any()) }
            }

            expect("notifies via Slack and continues to the next file when processing fails") {
                val dir = Files.createTempDirectory("process-path-test").toFile()
                val m2ts1 = File(dir, "a.m2ts").apply { writeBytes(byteArrayOf(1)) }
                val m2ts2 = File(dir, "b.m2ts").apply { writeBytes(byteArrayOf(1)) }
                every { fileProcessingPipeline.processFile(m2ts1, false, any(), any(), any()) } throws RuntimeException("boom")
                every { fileProcessingPipeline.processFile(m2ts2, false, any(), any(), any()) } returns
                    FileProcessingPipeline.Result.PROCESSED
                every { slackClient.notify(any()) } just Runs

                pathProcessingRunner.processPath(dir.toPath())

                verify { slackClient.notify(any()) }
                verify { fileProcessingPipeline.processFile(m2ts2, false, any(), any(), any()) }
            }

            expect("threads dryRun through to processFile") {
                val file = File.createTempFile("process-path-test", ".m2ts")
                every { fileProcessingPipeline.processFile(any(), any(), any(), any(), any()) } returns
                    FileProcessingPipeline.Result.PROCESSED

                pathProcessingRunner.processPath(file.toPath(), dryRun = true)

                verify { fileProcessingPipeline.processFile(file, true, any(), any(), any()) }
            }
        }
    })
