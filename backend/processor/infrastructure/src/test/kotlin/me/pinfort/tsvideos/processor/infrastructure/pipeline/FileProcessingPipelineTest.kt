package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Called
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import me.pinfort.tsvideos.core.command.CreatedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileTagCommand
import me.pinfort.tsvideos.core.command.ProcessingJobCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.command.VerifiedUploadCommand
import me.pinfort.tsvideos.core.component.CompressComponent
import me.pinfort.tsvideos.core.component.MainSplittedFileFinderComponent
import me.pinfort.tsvideos.core.component.NasDestinationResolver
import me.pinfort.tsvideos.core.component.NormalizeComponent
import me.pinfort.tsvideos.core.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.ExecutedFile
import me.pinfort.tsvideos.core.domain.ExecutedFileCheck
import me.pinfort.tsvideos.core.domain.ExecutedFileTag
import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.domain.ProcessingOutcome
import me.pinfort.tsvideos.core.domain.ProcessingStage
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.SplittedFile
import me.pinfort.tsvideos.core.exception.TsVideosException
import me.pinfort.tsvideos.core.external.samba.NasComponent
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.core.external.tool.AmatsukazeAddTaskClient
import me.pinfort.tsvideos.core.external.tool.DurationProbeClient
import me.pinfort.tsvideos.core.external.tool.TsSplitterClient
import me.pinfort.tsvideos.processor.infrastructure.external.ts.EmergencyBroadcastDetector
import me.pinfort.tsvideos.processor.infrastructure.external.tsselect.TsSelectClient
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class FileProcessingPipelineTest :
    ExpectSpec({
        lateinit var executedFileCommand: ExecutedFileCommand
        lateinit var executedFileTagCommand: ExecutedFileTagCommand
        lateinit var splittedFileCommand: SplittedFileCommand
        lateinit var createdFileCommand: CreatedFileCommand
        lateinit var programCommand: ProgramCommand
        lateinit var tsSelectClient: TsSelectClient
        lateinit var emergencyBroadcastDetector: EmergencyBroadcastDetector
        lateinit var tsSplitterClient: TsSplitterClient
        lateinit var amatsukazeAddTaskClient: AmatsukazeAddTaskClient
        lateinit var durationProbeClient: DurationProbeClient
        lateinit var nasComponent: NasComponent
        lateinit var sambaClient: SambaClient
        lateinit var jobs: ProcessingJobCommand
        lateinit var uploads: VerifiedUploadCommand
        lateinit var history: me.pinfort.tsvideos.core.command.ProcessingHistoryCommand
        lateinit var logger: Logger
        lateinit var fileProcessingPipeline: FileProcessingPipeline

        val properties =
            ProcessorToolConfigurationProperties(
                tsSplitterPath = "tsSplitter",
                amatsukazeAddTaskPath = "amatsukaze",
                ffprobePath = "ffprobe",
                amatsukaze =
                    ProcessorToolConfigurationProperties.Amatsukaze(
                        host = "localhost",
                        port = 32768,
                        defaultProfile = "30fps_light",
                        atxDivProfile = "30fps_light_atx_div",
                    ),
            )

        beforeTest {
            clearAllMocks()
            executedFileCommand = mockk()
            executedFileTagCommand = mockk(relaxed = true)
            splittedFileCommand = mockk()
            createdFileCommand = mockk()
            programCommand = mockk()
            tsSelectClient = mockk()
            emergencyBroadcastDetector = mockk()
            every { emergencyBroadcastDetector.detect(any(), any()) } returns
                EmergencyBroadcastDetector.Result(ewsDetected = false, superimposeDetected = false)
            tsSplitterClient = mockk()
            amatsukazeAddTaskClient = mockk()
            durationProbeClient = mockk()
            nasComponent = mockk()
            sambaClient = mockk()
            logger = mockk(relaxed = true)
            jobs = mockk(relaxed = true)
            uploads = mockk(relaxed = true)
            history = historyFixture()
            every { jobs.find(any()) } returns null
            every { jobs.findByPath(any()) } returns null
            every { jobs.claim(any()) } returns ProcessingJob(1, "key", "path", 1, 1, "CLAIMED", null, null)
            every { executedFileCommand.findByFile(any()) } returns null
            every { jobs.findByExecutedFile(any()) } returns ProcessingJob(1, "key", "path", 1, 1, "SUBMITTED", 1, null)
            every { jobs.claimCallback(any()) } returns "token"
            every { createdFileCommand.selectBySplittedFileId(any()) } returns emptyList()
            every { nasComponent.matchesSize(any(), any(), any()) } returns true
            every { sambaClient.resolvePathUnderBaseDir(any(), any()) } answers { secondArg() }
            fileProcessingPipeline =
                FileProcessingPipeline(
                    executedFileCommand = executedFileCommand,
                    executedFileTagCommand = executedFileTagCommand,
                    splittedFileCommand = splittedFileCommand,
                    programCommand = programCommand,
                    tsSelectClient = tsSelectClient,
                    emergencyBroadcastDetector = emergencyBroadcastDetector,
                    tsSplitterClient = tsSplitterClient,
                    amatsukazeAddTaskClient = amatsukazeAddTaskClient,
                    durationProbeClient = durationProbeClient,
                    mainSplittedFileFinderComponent = MainSplittedFileFinderComponent(),
                    compressComponent = CompressComponent(logger),
                    nasDestinationResolver = NasDestinationResolver(NormalizeComponent(), sambaClient),
                    processorToolConfigurationProperties = properties,
                    logger = logger,
                    processingJobCommand = jobs,
                    verifiedUploadCommand = uploads,
                    history = history,
                )
        }

        fun newOriginalFile(): File {
            val recordingDir = Files.createTempDirectory("process-file-test").toFile()
            val original = File(recordingDir, "[210708-0030][BSBS13_1][channel]title.m2ts")
            original.writeBytes("original ts content".toByteArray())
            return original
        }

        fun executedFileFixture(
            file: File,
            id: Long = 100,
        ) = ExecutedFile(
            id = id,
            file = file.absolutePath,
            drops = 0,
            size = file.length(),
            recordedAt = LocalDateTime.of(2021, 7, 8, 0, 30),
            channel = "BSBS13_1",
            title = "title.m2ts",
            channelName = "channel",
            duration = 100.0,
            status = ExecutedFile.Status.DROPCHECKED,
        )

        context("processFile - dry run") {
            expect("checks the recording without creating files or invoking mutating dependencies") {
                val original = newOriginalFile()
                every { programCommand.findByName(original.name) } returns null
                every { tsSelectClient.check(any(), any()) } returns 0
                every { durationProbeClient.probe(any(), any()) } returns 100.0

                fileProcessingPipeline.processFile(original, dryRun = true) shouldBe FileProcessingPipeline.Result.DRY_RUN

                verify { logger.info(match<String> { it.contains("would register, split, compress, upload and submit encoding") }) }
                verify { jobs wasNot Called }
                original.parentFile.listFiles()!!.toList() shouldBe listOf(original)
                verify(exactly = 1) { tsSelectClient.check(original, any()) }
                verify(exactly = 1) { durationProbeClient.probe(original, any()) }
                verify {
                    listOf(
                        executedFileCommand,
                        splittedFileCommand,
                        createdFileCommand,
                        tsSplitterClient,
                        nasComponent,
                        amatsukazeAddTaskClient,
                    ) wasNot Called
                }
                verify(exactly = 0) { programCommand.insert(any(), any(), any()) }
            }
            expect("does not run rollback when read-only validation fails") {
                val missing = File("/nonexistent/recording.m2ts")
                shouldThrow<TsVideosException> { fileProcessingPipeline.processFile(missing, dryRun = true) }
                verify { listOf(executedFileCommand, splittedFileCommand, createdFileCommand, programCommand) wasNot Called }
            }
        }

        context("processFile - shared inspection") {
            for (dryRun in listOf(false, true)) {
                expect("rejects directories before calling collaborators, dryRun=$dryRun") {
                    val directory = Files.createTempDirectory("inspection-directory").toFile()
                    try {
                        shouldThrow<TsVideosException> { fileProcessingPipeline.processFile(directory, dryRun) }
                        verify {
                            listOf(programCommand, executedFileCommand, tsSelectClient, durationProbeClient) wasNot Called
                        }
                    } finally {
                        directory.delete()
                    }
                }
                expect("inspection failure never rolls back existing records, dryRun=$dryRun") {
                    val original = newOriginalFile()
                    every { programCommand.findByName(original.name) } returns null
                    every { tsSelectClient.check(any(), any()) } throws TsVideosException("inspection failed")
                    shouldThrow<TsVideosException> { fileProcessingPipeline.processFile(original, dryRun) }
                    verify { listOf(splittedFileCommand, createdFileCommand) wasNot Called }
                    verify(exactly = 0) { executedFileCommand.delete(any(), any()) }
                    original.exists() shouldBe true
                    if (dryRun) {
                        verify(exactly = 0) { history.begin(any(), any()) }
                    } else {
                        verify { history.failed(44, any()) }
                        verify { jobs.transition(any(), "CLAIMED", "RETRYABLE") }
                    }
                }
            }
        }

        context("processFile - happy path") {
            expect("processes all four stages and returns PROCESSED") {
                val original = newOriginalFile()
                val outDir = File(original.parentFile, "tssplitter")
                val executedFile = executedFileFixture(original)
                lateinit var splitFile: File

                every { programCommand.findByName(original.name) } returns null
                every { tsSelectClient.check(any(), any()) } returns 0
                every { emergencyBroadcastDetector.detect(original, any()) } returns
                    EmergencyBroadcastDetector.Result(ewsDetected = true, superimposeDetected = false)
                every { durationProbeClient.probe(any(), any()) } returns 100.0
                every {
                    executedFileCommand.insert(any(), any(), any(), any(), any(), any(), any(), any(), any())
                } returns executedFile
                every { programCommand.insert(any(), any(), any()) } returns mockk()

                every { tsSplitterClient.split(any(), any(), any()) } answers {
                    outDir.mkdirs()
                    splitFile = File(outDir, "${original.nameWithoutExtension}_1.m2ts")
                    splitFile.writeBytes("split content".toByteArray())
                    0
                }

                every {
                    splittedFileCommand.insert(any(), any(), any(), any(), any())
                } answers {
                    SplittedFile(
                        id = 200,
                        executedFileId = executedFile.id,
                        file = splitFile.absolutePath,
                        size = splitFile.length(),
                        duration = 100.0,
                        status = SplittedFile.Status.REGISTERED,
                    )
                }
                every { executedFileCommand.updateStatus(any(), any(), any()) } returns executedFile
                every { splittedFileCommand.updateStatus(any(), any(), any()) } answers { firstArg() }

                every {
                    sambaClient.resolvePathUnderBaseDir(SambaClient.NasType.ORIGINAL_STORE_NAS, any())
                } answers { "nas-base-dir/${secondArg<String>()}" }

                every { nasComponent.uploadResource(any(), any(), any(), any()) } returns true
                every {
                    createdFileCommand.insert(any(), any(), any(), any(), any(), any(), any())
                } returns
                    CreatedFile(
                        id = 300,
                        splittedFileId = 200,
                        file = "target",
                        size = 1,
                        mime = "video/vnd.dlna.mpeg-tts",
                        encoding = "gzip",
                        status = CreatedFile.Status.FILE_MOVED,
                    )

                every { executedFileCommand.find(executedFile.id) } returns executedFile
                every { amatsukazeAddTaskClient.addTask(any(), any(), any()) } returns 0

                val result = fileProcessingPipeline.processFile(original)

                result shouldBe FileProcessingPipeline.Result.PROCESSED
                verifyOrder {
                    history.begin(any(), any())
                    history.stage<Any?>(44, ProcessingStage.INSPECT, any(), any())
                    history.stage<Any?>(44, ProcessingStage.SPLIT, any(), any())
                    history.stage<Any?>(44, ProcessingStage.COMPRESS, any(), any())
                    history.stage<Any?>(44, ProcessingStage.UPLOAD_ORIGINAL, any(), any())
                    history.stage<Any?>(44, ProcessingStage.FINALIZE, any(), any())
                    history.stage<Any?>(44, ProcessingStage.SUBMIT_ENCODER, any(), any())
                    history.finish(44, ProcessingOutcome.COMPLETED, null)
                }
                verify { amatsukazeAddTaskClient.addTask(any(), any(), "30fps_light") }
                // the emergency broadcast detection result is recorded as tags, along with the check itself
                verify {
                    executedFileTagCommand.recordCheck(
                        executedFile.id,
                        ExecutedFileCheck.EMERGENCY_BROADCAST,
                        setOf(ExecutedFileTag.EWS),
                        false,
                    )
                }
                verify(exactly = 0) { executedFileCommand.delete(any(), any()) }
                verify {
                    uploads.upload(
                        1,
                        "original",
                        any(),
                        SambaClient.NasType.ORIGINAL_STORE_NAS,
                        match { it.startsWith("nas-base-dir/") },
                        any(),
                    )
                    uploads.register(any(), 200, "video/vnd.dlna.mpeg-tts", "gzip")
                    uploads.deleteLocal(any())
                }
            }
        }

        context("acknowledgement failure") {
            expect("does not resubmit after encoder success followed by DB acknowledgement failure") {
                val original = newOriginalFile()
                val outDir = File(original.parentFile, "tssplitter")
                val executedFile = executedFileFixture(original)
                lateinit var splitFile: File

                every { programCommand.findByName(original.name) } returns null
                every { tsSelectClient.check(any(), any()) } returns 0
                every { durationProbeClient.probe(any(), any()) } returns 100.0
                every {
                    executedFileCommand.insert(any(), any(), any(), any(), any(), any(), any(), any(), any())
                } returns executedFile
                every { programCommand.insert(any(), any(), any()) } returns mockk()

                every { tsSplitterClient.split(any(), any(), any()) } answers {
                    outDir.mkdirs()
                    splitFile = File(outDir, "${original.nameWithoutExtension}_1.m2ts")
                    splitFile.writeBytes("split content".toByteArray())
                    0
                }

                every {
                    splittedFileCommand.insert(any(), any(), any(), any(), any())
                } answers {
                    SplittedFile(
                        id = 200,
                        executedFileId = executedFile.id,
                        file = splitFile.absolutePath,
                        size = splitFile.length(),
                        duration = 100.0,
                        status = SplittedFile.Status.REGISTERED,
                    )
                }
                every { executedFileCommand.updateStatus(any(), any(), any()) } returns executedFile
                every { splittedFileCommand.updateStatus(any(), any(), any()) } answers { firstArg() }

                every {
                    sambaClient.resolvePathUnderBaseDir(SambaClient.NasType.ORIGINAL_STORE_NAS, any())
                } answers { "nas-base-dir/${secondArg<String>()}" }

                every { nasComponent.uploadResource(any(), any(), any(), any()) } returns true
                every {
                    createdFileCommand.insert(any(), any(), any(), any(), any(), any(), any())
                } returns
                    CreatedFile(
                        id = 300,
                        splittedFileId = 200,
                        file = "target",
                        size = 1,
                        mime = "video/vnd.dlna.mpeg-tts",
                        encoding = "gzip",
                        status = CreatedFile.Status.FILE_MOVED,
                    )

                every { executedFileCommand.find(executedFile.id) } returns executedFile
                every { amatsukazeAddTaskClient.addTask(any(), any(), any()) } returns 0

                every { jobs.acknowledgeSubmission(any()) } throws IllegalStateException("DB commit failed")
                shouldThrow<IllegalStateException> { fileProcessingPipeline.processFile(original) }
                every { jobs.find(any()) } returns
                    ProcessingJob(
                        1,
                        "key",
                        original.canonicalPath,
                        original.length(),
                        original.lastModified(),
                        "SUBMISSION_UNKNOWN",
                        10,
                        null,
                    )
                shouldThrow<IllegalStateException> { fileProcessingPipeline.processFile(original) }

                verify(exactly = 1) { amatsukazeAddTaskClient.addTask(any(), any(), any()) }
                verify { amatsukazeAddTaskClient.addTask(any(), any(), "30fps_light") }
                verify(exactly = 0) { executedFileCommand.delete(any(), any()) }
                verify {
                    uploads.upload(
                        1,
                        "original",
                        any(),
                        SambaClient.NasType.ORIGINAL_STORE_NAS,
                        match { it.startsWith("nas-base-dir/") },
                        any(),
                    )
                    uploads.register(any(), 200, "video/vnd.dlna.mpeg-tts", "gzip")
                    uploads.deleteLocal(any())
                }
            }
        }
        expect("pre-existing split artifacts are not deleted by rejected processing") {
            val original = newOriginalFile()
            val executed = executedFileFixture(original)
            val outDir = File(original.parentFile, "tssplitter").apply { mkdirs() }
            val existing = File(outDir, "${original.nameWithoutExtension}_1.m2ts").apply { writeText("unowned") }
            every { programCommand.findByName(original.name) } returns null
            every { tsSelectClient.check(any(), any()) } returns 0
            every { durationProbeClient.probe(any(), any()) } returns 100.0
            every { executedFileCommand.insert(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns executed
            every { programCommand.insert(any(), any(), any()) } returns mockk()
            every { programCommand.deleteByExecutedFileId(any(), any()) } just Runs
            every { executedFileCommand.delete(any(), any()) } just Runs
            shouldThrow<TsVideosException> { fileProcessingPipeline.processFile(original) }
            existing.readText() shouldBe "unowned"
            verify(exactly = 0) { tsSplitterClient.split(any(), any(), any()) }
            verify(exactly = 0) { splittedFileCommand.delete(any(), any()) }
        }
        context("processFile - already registered") {
            expect("returns SKIPPED_ALREADY_REGISTERED without running drop chk") {
                val original = File.createTempFile("process-file-test", ".m2ts")
                original.writeBytes("x".toByteArray())
                every { jobs.find(any()) } returns
                    ProcessingJob(1, "key", original.canonicalPath, original.length(), original.lastModified(), "SUBMITTED", 10, null)
                val program = mockk<Program>()
                every { programCommand.findByName(original.name) } returns program

                val result = fileProcessingPipeline.processFile(original)

                result shouldBe FileProcessingPipeline.Result.SKIPPED_ALREADY_REGISTERED
                verify(exactly = 0) { tsSelectClient.check(any(), any()) }
                verify(exactly = 0) { emergencyBroadcastDetector.detect(any(), any()) }
            }
        }

        context("processFile - blocked recordings alert instead of skipping") {
            expect("a recording name registered under another path fails before claiming") {
                val original = newOriginalFile()
                every { programCommand.findByName(original.name) } returns mockk<Program>()

                val e = shouldThrow<IllegalStateException> { fileProcessingPipeline.processFile(original) }

                e.message shouldBe
                    "Recording name ${original.name} already registered; run `tvpcli reset` on that recording before processing"
                verify(exactly = 0) { jobs.claim(any()) }
                verify(exactly = 0) { executedFileCommand.insert(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            }

            expect("a CLAIMED job names its age and possible causes") {
                val original = newOriginalFile()
                every { jobs.find(any()) } returns
                    ProcessingJob(1, "key", original.canonicalPath, 1, 1, "CLAIMED", 10, null, null, LocalDateTime.of(2026, 10, 1, 3, 4))

                val e = shouldThrow<IllegalStateException> { fileProcessingPipeline.processFile(original) }

                e.message shouldContain "Job 1 is CLAIMED (last updated 2026-10-01T03:04 UTC)"
                e.message shouldContain "after its upload started"
                verify(exactly = 0) { tsSelectClient.check(any(), any()) }
            }
        }

        context("processFile - drop chk failure") {
            expect("rejects a missing file before registering rollback") {
                val missing = File("/nonexistent/path/[210708-0030][BSBS13_1][channel]title.m2ts")

                shouldThrow<TsVideosException> {
                    fileProcessingPipeline.processFile(missing)
                }

                verify(exactly = 0) { executedFileCommand.findByFile(missing.absolutePath) }
                verify(exactly = 0) { executedFileCommand.delete(any(), any()) }
            }
        }

        context("processFile - emergency broadcast detection failure") {
            expect("keeps processing and leaves the recording unchecked") {
                val original = newOriginalFile()
                val executedFile = executedFileFixture(original)

                every { programCommand.findByName(original.name) } returns null
                every { tsSelectClient.check(any(), any()) } returns 0
                every { emergencyBroadcastDetector.detect(original, any()) } throws TsVideosException("not a transport stream")
                every { durationProbeClient.probe(any(), any()) } returns 100.0
                every {
                    executedFileCommand.insert(any(), any(), any(), any(), any(), any(), any(), any(), any())
                } returns executedFile
                every { programCommand.insert(any(), any(), any()) } returns mockk()
                // stop at the next stage; only registration matters here
                every { tsSplitterClient.split(any(), any(), any()) } throws TsVideosException("ts split failed")
                every { splittedFileCommand.selectByExecutedFileId(executedFile.id) } returns emptyList()
                every { programCommand.deleteByExecutedFileId(any(), any()) } just Runs
                every { executedFileCommand.delete(any(), any()) } just Runs

                val e = shouldThrow<TsVideosException> { fileProcessingPipeline.processFile(original) }

                e.message shouldBe "ts split failed"
                verify { programCommand.insert(original.name, executedFile.id, false) }
                verify(exactly = 0) { executedFileTagCommand.recordCheck(any(), any(), any(), any()) }
            }

            expect("keeps the registration when saving the tags fails") {
                val original = newOriginalFile()
                val executedFile = executedFileFixture(original)

                every { programCommand.findByName(original.name) } returns null
                every { tsSelectClient.check(any(), any()) } returns 0
                every { durationProbeClient.probe(any(), any()) } returns 100.0
                every {
                    executedFileCommand.insert(any(), any(), any(), any(), any(), any(), any(), any(), any())
                } returns executedFile
                every { programCommand.insert(any(), any(), any()) } returns mockk()
                every { executedFileTagCommand.recordCheck(any(), any(), any(), any()) } throws
                    RuntimeException("Table 'executed_file_tag' doesn't exist")
                // stop at the next stage; reaching it shows the registration stage did not fail
                every { tsSplitterClient.split(any(), any(), any()) } throws TsVideosException("ts split failed")
                every { splittedFileCommand.selectByExecutedFileId(executedFile.id) } returns emptyList()
                every { programCommand.deleteByExecutedFileId(any(), any()) } just Runs
                every { executedFileCommand.delete(any(), any()) } just Runs

                val e = shouldThrow<TsVideosException> { fileProcessingPipeline.processFile(original) }

                e.message shouldBe "ts split failed"
                verify { executedFileTagCommand.recordCheck(executedFile.id, ExecutedFileCheck.EMERGENCY_BROADCAST, any(), any()) }
                verify { tsSplitterClient.split(any(), any(), any()) }
            }

            expect("dry run does not fail") {
                val original = newOriginalFile()
                every { programCommand.findByName(original.name) } returns null
                every { tsSelectClient.check(any(), any()) } returns 0
                every { emergencyBroadcastDetector.detect(original, any()) } throws TsVideosException("not a transport stream")
                every { durationProbeClient.probe(any(), any()) } returns 100.0

                fileProcessingPipeline.processFile(original, dryRun = true) shouldBe FileProcessingPipeline.Result.DRY_RUN
            }
        }

        context("processFile - registration failure") {
            expect("rolls back a partially registered recording") {
                val original = newOriginalFile()
                val executedFile = executedFileFixture(original)
                every { programCommand.findByName(original.name) } returns null
                every { tsSelectClient.check(any(), any()) } returns 0
                every { durationProbeClient.probe(any(), any()) } returns 100.0
                every {
                    executedFileCommand.insert(any(), any(), any(), any(), any(), any(), any(), any(), any())
                } returns executedFile
                every { programCommand.insert(any(), any(), any()) } throws TsVideosException("registration failed")
                every { executedFileCommand.findByFile(original.absolutePath) } returns null
                every { programCommand.deleteByExecutedFileId(any(), any()) } just Runs
                every { executedFileCommand.delete(any(), any()) } just Runs

                shouldThrow<TsVideosException> { fileProcessingPipeline.processFile(original) }

                verifyOrder {
                    programCommand.insert(original.name, executedFile.id, false)
                    executedFileCommand.delete(executedFile, false)
                }
                verify { tsSplitterClient wasNot Called }
            }
        }

        context("processFile - retry decision") {
            fun stubFailingSplit(original: File): ExecutedFile {
                val executedFile = executedFileFixture(original)
                every { programCommand.findByName(original.name) } returns null
                every { tsSelectClient.check(any(), any()) } returns 0
                every { durationProbeClient.probe(any(), any()) } returns 100.0
                every {
                    executedFileCommand.insert(any(), any(), any(), any(), any(), any(), any(), any(), any())
                } returns executedFile
                every { programCommand.insert(any(), any(), any()) } returns mockk()
                every { tsSplitterClient.split(any(), any(), any()) } returns 1
                every { splittedFileCommand.selectByExecutedFileId(executedFile.id) } returns emptyList()
                every { programCommand.deleteByExecutedFileId(any(), any()) } just Runs
                every { executedFileCommand.delete(any(), any()) } just Runs
                return executedFile
            }

            // Stubs every stage up to the original-store upload; returns the archive path compress would create.
            fun stubThroughUpload(original: File): File {
                val executedFile = stubFailingSplit(original)
                val outDir = File(original.parentFile, "tssplitter")
                val splitFile = File(outDir, "${original.nameWithoutExtension}_1.m2ts")
                every { tsSplitterClient.split(any(), any(), any()) } answers {
                    outDir.mkdirs()
                    splitFile.writeBytes("split content".toByteArray())
                    0
                }
                every { splittedFileCommand.insert(any(), any(), any(), any(), any()) } returns
                    SplittedFile(200, executedFile.id, splitFile.absolutePath, 13, 100.0, SplittedFile.Status.REGISTERED)
                every { splittedFileCommand.delete(any(), any()) } just Runs
                every { executedFileCommand.updateStatus(any(), any(), any()) } returns executedFile
                every {
                    sambaClient.resolvePathUnderBaseDir(SambaClient.NasType.ORIGINAL_STORE_NAS, any())
                } answers { "nas-base-dir/${secondArg<String>()}" }
                return File(outDir, "${splitFile.name}.gz")
            }

            expect("a failed job link rolls the registration back and releases the job for retry") {
                val original = newOriginalFile()
                val executedFile = stubFailingSplit(original)
                every { jobs.link(any(), any()) } throws RuntimeException("db blip")

                shouldThrow<RuntimeException> { fileProcessingPipeline.processFile(original) }

                verify { executedFileCommand.delete(executedFile, false) }
                verify { tsSplitterClient wasNot Called }
                verify { jobs.transition(any(), "CLAIMED", "RETRYABLE") }
            }

            expect("a failure to start history releases the claim for retry before any inspection") {
                val original = newOriginalFile()
                every { programCommand.findByName(original.name) } returns null
                every { history.begin(any(), any()) } throws RuntimeException("db blip")

                shouldThrow<RuntimeException> { fileProcessingPipeline.processFile(original) }

                verify(exactly = 0) { tsSelectClient.check(any(), any()) }
                verify(exactly = 0) { history.failed(any(), any()) }
                verify { jobs.transition(any(), "CLAIMED", "RETRYABLE") }
            }

            expect("a compress failure removes the partial archive and releases the job for retry") {
                val original = newOriginalFile()
                val archive = stubThroughUpload(original)

                shouldThrow<IllegalStateException> {
                    fileProcessingPipeline.processFile(original, onCompressProgress = { _, _ -> error("disk full") })
                }

                archive.exists() shouldBe false
                verify(exactly = 0) { nasComponent.uploadResource(any(), any(), any(), any()) }
                verify { jobs.transition(any(), "CLAIMED", "RETRYABLE") }
            }

            expect("an upload failure keeps the job claimed for reconciliation") {
                val original = newOriginalFile()
                stubThroughUpload(original)
                every { nasComponent.uploadResource(any(), any(), any(), any()) } throws RuntimeException("smb timeout")

                shouldThrow<RuntimeException> { fileProcessingPipeline.processFile(original) }

                verify(exactly = 0) { jobs.transition(any(), "CLAIMED", "RETRYABLE") }
                verify { logger.error(match<String> { it.contains("requires reconciliation") }, any<Throwable>()) }
            }

            expect("a clean rollback releases the job for retry") {
                val original = newOriginalFile()
                stubFailingSplit(original)

                shouldThrow<TsVideosException> { fileProcessingPipeline.processFile(original) }

                verify { jobs.transition(any(), "CLAIMED", "RETRYABLE") }
            }

            expect("a partially failed rollback keeps the job claimed for reconciliation") {
                val original = newOriginalFile()
                stubFailingSplit(original)
                every { executedFileCommand.delete(any(), any()) } throws RuntimeException("db down")

                val e = shouldThrow<TsVideosException> { fileProcessingPipeline.processFile(original) }

                e.suppressed.map { it.message } shouldBe listOf("db down")
                verify(exactly = 0) { jobs.transition(any(), "CLAIMED", "RETRYABLE") }
            }

            expect("a failure to release the job does not mask the processing failure") {
                val original = newOriginalFile()
                stubFailingSplit(original)
                every { jobs.transition(any(), "CLAIMED", "RETRYABLE") } throws IllegalStateException("state changed")

                val e = shouldThrow<TsVideosException> { fileProcessingPipeline.processFile(original) }

                e.message shouldBe "TsSplitter failed, exitCode=1, originalFile=$original"
                e.suppressed.map { it.message } shouldBe listOf("state changed")
                verify { logger.error(match<String> { it.contains("requires reconciliation") }, e) }
            }
        }

        context("processFile - ts split failure") {
            expect("rolls back ts split and drop chk when TsSplitter exits non-zero") {
                val original = newOriginalFile()
                val executedFile = executedFileFixture(original)

                every { programCommand.findByName(original.name) } returns null
                every { tsSelectClient.check(any(), any()) } returns 0
                every { durationProbeClient.probe(any(), any()) } returns 100.0
                every {
                    executedFileCommand.insert(any(), any(), any(), any(), any(), any(), any(), any(), any())
                } returns executedFile
                every { programCommand.insert(any(), any(), any()) } returns mockk()
                every { tsSplitterClient.split(any(), any(), any()) } returns 1

                every { splittedFileCommand.selectByExecutedFileId(executedFile.id) } returns emptyList()
                every { executedFileCommand.findByFile(original.absolutePath) } returns null
                every { programCommand.deleteByExecutedFileId(any(), any()) } just Runs
                every { executedFileCommand.delete(any(), any()) } just Runs

                shouldThrow<TsVideosException> {
                    fileProcessingPipeline.processFile(original)
                }

                verifyOrder {
                    splittedFileCommand.selectByExecutedFileId(executedFile.id)
                    programCommand.deleteByExecutedFileId(executedFile.id, false)
                    executedFileCommand.delete(executedFile, false)
                }
            }
        }

        context("processFile - compress skipped") {
            expect("rejects stale compressed file before upload and rolls back the split") {
                val original = newOriginalFile()
                val outDir = File(original.parentFile, "tssplitter")
                val executedFile = executedFileFixture(original)
                lateinit var splitFile: File

                every { programCommand.findByName(original.name) } returns null
                every { tsSelectClient.check(any(), any()) } returns 0
                every { durationProbeClient.probe(any(), any()) } returns 100.0
                every {
                    executedFileCommand.insert(any(), any(), any(), any(), any(), any(), any(), any(), any())
                } returns executedFile
                every { programCommand.insert(any(), any(), any()) } returns mockk()

                every { tsSplitterClient.split(any(), any(), any()) } answers {
                    outDir.mkdirs()
                    splitFile = File(outDir, "${original.nameWithoutExtension}_1.m2ts")
                    splitFile.writeBytes("split content".toByteArray())
                    // simulate a leftover compressed file from a previous, interrupted run
                    File(outDir, "${splitFile.name}.gz").writeBytes("stale".toByteArray())
                    0
                }
                every {
                    splittedFileCommand.insert(any(), any(), any(), any(), any())
                } answers {
                    SplittedFile(
                        id = 200,
                        executedFileId = executedFile.id,
                        file = splitFile.absolutePath,
                        size = splitFile.length(),
                        duration = 100.0,
                        status = SplittedFile.Status.REGISTERED,
                    )
                }
                every { executedFileCommand.updateStatus(any(), any(), any()) } returns executedFile

                every { executedFileCommand.find(executedFile.id) } returns executedFile
                every { amatsukazeAddTaskClient.addTask(any(), any(), any()) } returns 0

                shouldThrow<IllegalStateException> { fileProcessingPipeline.processFile(original) }
                original.exists() shouldBe true
                // Nothing reached the NAS: the split is rolled back, but the archive this attempt did not create is kept.
                splitFile.exists() shouldBe false
                File(outDir, "${splitFile.name}.gz").exists() shouldBe true
                verify(exactly = 0) { uploads.upload(any(), any(), any(), any(), any(), any()) }
                verify(exactly = 0) { amatsukazeAddTaskClient.addTask(any(), any(), any()) }
            }
        }

        context("processFile - amatsukaze failure") {
            expect("preserves encoder input and records ambiguous submission on failure") {
                val original = newOriginalFile()
                val outDir = File(original.parentFile, "tssplitter")
                val executedFile = executedFileFixture(original)
                lateinit var splitFile: File
                lateinit var mainSplittedFile: SplittedFile

                every { programCommand.findByName(original.name) } returns null
                every { tsSelectClient.check(any(), any()) } returns 0
                every { durationProbeClient.probe(any(), any()) } returns 100.0
                every {
                    executedFileCommand.insert(any(), any(), any(), any(), any(), any(), any(), any(), any())
                } returns executedFile
                every { programCommand.insert(any(), any(), any()) } returns mockk()

                every { tsSplitterClient.split(any(), any(), any()) } answers {
                    outDir.mkdirs()
                    splitFile = File(outDir, "${original.nameWithoutExtension}_1.m2ts")
                    splitFile.writeBytes("split content".toByteArray())
                    0
                }
                every {
                    splittedFileCommand.insert(any(), any(), any(), any(), any())
                } answers {
                    mainSplittedFile =
                        SplittedFile(
                            id = 200,
                            executedFileId = executedFile.id,
                            file = splitFile.absolutePath,
                            size = splitFile.length(),
                            duration = 100.0,
                            status = SplittedFile.Status.REGISTERED,
                        )
                    mainSplittedFile
                }
                every { executedFileCommand.updateStatus(any(), any(), any()) } returns executedFile
                every { splittedFileCommand.updateStatus(any(), any(), any()) } answers { firstArg() }

                every { nasComponent.uploadResource(any(), any(), any(), any()) } returns true
                every {
                    createdFileCommand.insert(any(), any(), any(), any(), any(), any(), any())
                } returns
                    CreatedFile(
                        id = 300,
                        splittedFileId = 200,
                        file = "target",
                        size = 1,
                        mime = "video/vnd.dlna.mpeg-tts",
                        encoding = "gzip",
                        status = CreatedFile.Status.FILE_MOVED,
                    )

                every { executedFileCommand.find(executedFile.id) } returns executedFile
                every { amatsukazeAddTaskClient.addTask(any(), any(), any()) } throws RuntimeException("amatsukaze unreachable")

                every { createdFileCommand.selectBySplittedFileId(200) } returns
                    listOf(
                        CreatedFile(
                            id = 300,
                            splittedFileId = 200,
                            file = "target",
                            size = 1,
                            mime = "video/vnd.dlna.mpeg-tts",
                            encoding = "gzip",
                            status = CreatedFile.Status.FILE_MOVED,
                        ),
                    )
                every { createdFileCommand.delete(any(), any()) } returns SambaClient.NasType.ORIGINAL_STORE_NAS
                every { splittedFileCommand.selectByExecutedFileId(executedFile.id) } returns emptyList()
                every { executedFileCommand.findByFile(original.absolutePath) } returns null
                every { programCommand.deleteByExecutedFileId(any(), any()) } just Runs
                every { executedFileCommand.delete(any(), any()) } just Runs

                shouldThrow<RuntimeException> {
                    fileProcessingPipeline.processFile(original)
                }

                verify { jobs.transition(any(), "CLAIMED", "SUBMISSION_UNKNOWN") }
                verify(exactly = 0) { createdFileCommand.delete(any(), any()) }
                verify(exactly = 0) { executedFileCommand.delete(any(), any()) }
                splitFile.exists() shouldBe true
            }
        }
    })
