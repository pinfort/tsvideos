package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.andThenJust
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.catalog.api.ExecutedFileCommand
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.api.SplittedFileCommand
import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.core.command.VerifiedUploadCommand
import me.pinfort.tsvideos.processing.api.ProcessingJobCommand
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

private class ResetFixture {
    val root: File = Files.createTempDirectory("reset-test").toFile()
    val recordingDir = File(root, "myprogram").also { it.mkdirs() }
    val tssplitterDir = File(recordingDir, "tssplitter").also { it.mkdirs() }
    val succeededDir = File(tssplitterDir, "succeeded").also { it.mkdirs() }
    val executedFile = File(recordingDir, "rec.m2ts").also { it.writeBytes("executed".toByteArray()) }
    val splitFile = File(tssplitterDir, "rec_1.m2ts").also { it.writeBytes("splitted".toByteArray()) }
    val succeededSplitFile = File(succeededDir, "rec_1.m2ts").also { it.writeBytes("splitted".toByteArray()) }
}

class ResetRunnerTest :
    ExpectSpec({
        lateinit var executedFileCommand: ExecutedFileCommand
        lateinit var programCommand: ProgramCommand
        lateinit var splittedFileCommand: SplittedFileCommand
        lateinit var jobs: ProcessingJobCommand
        lateinit var uploads: VerifiedUploadCommand
        lateinit var logger: Logger
        lateinit var resetRunner: ResetRunner

        fun executedFileOf(fixture: ResetFixture) =
            ExecutedFile(
                id = 1,
                file = fixture.executedFile.absolutePath,
                drops = 0,
                size = 8,
                recordedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 100.0,
                status = ExecutedFile.Status.SPLITTED,
            )

        val program =
            Program(
                id = 5,
                name = "rec.m2ts",
                executedFileId = 1,
                status = Program.Status.REGISTERED,
                drops = 0,
                size = 8,
                recordedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 100.0,
            )

        fun splittedFileOf(fixture: ResetFixture) =
            SplittedFile(
                id = 10,
                executedFileId = 1,
                file = fixture.splitFile.absolutePath,
                size = 8,
                duration = 100.0,
                status = SplittedFile.Status.REGISTERED,
            )

        beforeTest {
            clearAllMocks()
            executedFileCommand = mockk()
            programCommand = mockk()
            splittedFileCommand = mockk()
            logger = mockk(relaxed = true)
            jobs = mockk(relaxed = true)
            uploads = mockk(relaxed = true)
            every { jobs.findByPath(any()) } returns null
            every { jobs.findByExecutedFile(any()) } returns null
            resetRunner =
                ResetRunner(executedFileCommand, programCommand, splittedFileCommand, logger, jobs, uploads)
        }

        expect("repeated reset finishes manifest cleanup after program records have been deleted") {
            val fixture = ResetFixture()
            val job =
                me.pinfort.tsvideos.processing.domain.ProcessingJob(
                    12,
                    "key",
                    fixture.executedFile.canonicalPath,
                    8,
                    1,
                    "RESETTING",
                    1,
                    null,
                )
            var deleted = false
            var failCleanup = true
            every { jobs.findByExecutedFile(1) } returns job
            every { jobs.findByPath(fixture.executedFile.canonicalPath) } returns job
            every { executedFileCommand.findByFile(any()) } answers { if (deleted) null else executedFileOf(fixture) }
            every { executedFileCommand.find(1) } returns null
            every { programCommand.findByExecutedFileId(1) } answers { if (deleted) null else program }
            every { splittedFileCommand.selectByExecutedFileId(1) } answers
                { if (deleted) emptyList() else listOf(splittedFileOf(fixture)) }
            every { programCommand.delete(program, false) } answers { deleted = true }
            every { uploads.retireAfterReset(12) } answers {
                if (failCleanup) {
                    failCleanup = false
                    error("NAS unavailable")
                }
            }
            try {
                shouldThrow<IllegalStateException> { resetRunner.run(fixture.executedFile.toPath()) }
                verify(exactly = 0) { jobs.reset(1) }
                resetRunner.run(fixture.executedFile.toPath(), dryRun = true)
                verify(exactly = 1) { uploads.retireAfterReset(12) }
                resetRunner.run(fixture.executedFile.toPath())
                verify(exactly = 2) { uploads.retireAfterReset(12) }
                verify(exactly = 1) { jobs.reset(1) }
                verify(exactly = 1) { programCommand.delete(program, false) }
                fixture.executedFile.exists() shouldBe true
                fixture.splitFile.exists() shouldBe true
                fixture.succeededSplitFile.exists() shouldBe true
            } finally {
                fixture.root.deleteRecursively()
            }
        }
        context("run") {
            expect("deletes the program, its rows and the local splitted files") {
                val fixture = ResetFixture()
                every { executedFileCommand.findByFile(fixture.executedFile.absolutePath) } returns executedFileOf(fixture)
                every { programCommand.findByExecutedFileId(1) } returns program
                every { splittedFileCommand.selectByExecutedFileId(1) } returns listOf(splittedFileOf(fixture))
                every { programCommand.delete(any(), any()) } just Runs

                resetRunner.run(fixture.executedFile.toPath(), dryRun = false)

                fixture.splitFile.exists() shouldBe false
                fixture.succeededSplitFile.exists() shouldBe false
                fixture.executedFile.exists() shouldBe true
                verify { programCommand.delete(program, false) }
            }

            expect("keeps local files in dry-run mode but still calls delete") {
                val fixture = ResetFixture()
                every { executedFileCommand.findByFile(fixture.executedFile.absolutePath) } returns executedFileOf(fixture)
                every { programCommand.findByExecutedFileId(1) } returns program
                every { splittedFileCommand.selectByExecutedFileId(1) } returns listOf(splittedFileOf(fixture))
                every { programCommand.delete(any(), any()) } just Runs

                resetRunner.run(fixture.executedFile.toPath(), dryRun = true)

                fixture.splitFile.exists() shouldBe true
                fixture.succeededSplitFile.exists() shouldBe true
                verify { programCommand.delete(program, true) }
            }

            expect("an interrupted reset can be replayed while its rows still exist") {
                val fixture = ResetFixture()
                every { executedFileCommand.findByFile(fixture.executedFile.absolutePath) } returns executedFileOf(fixture)
                every { programCommand.findByExecutedFileId(1) } returns program
                every { splittedFileCommand.selectByExecutedFileId(1) } returns listOf(splittedFileOf(fixture))
                every { programCommand.delete(any(), any()) } throws RuntimeException("nas down") andThenJust Runs

                shouldThrow<RuntimeException> { resetRunner.run(fixture.executedFile.toPath(), dryRun = false) }
                fixture.splitFile.exists() shouldBe true
                verify(exactly = 0) { jobs.reset(any()) }

                resetRunner.run(fixture.executedFile.toPath(), dryRun = false)

                fixture.splitFile.exists() shouldBe false
                verify(exactly = 2) { jobs.prepareReset(1) }
                verify(exactly = 1) { jobs.reset(1) }
            }

            expect("leaves a RESETTING job for manual cleanup once its rows are gone") {
                every { executedFileCommand.findByFile(any()) } returns null

                resetRunner.run(File("/rec/half-reset.m2ts").toPath(), dryRun = false)

                verify(exactly = 0) { jobs.prepareReset(any()) }
                verify(exactly = 0) { jobs.reset(any()) }
            }

            expect("finds a pre-064 executed file registered through a symlinked path") {
                val fixture = ResetFixture()
                val linkRoot = Files.createTempDirectory("reset-link").toFile()
                try {
                    val link = Files.createSymbolicLink(File(linkRoot, "link").toPath(), fixture.root.toPath())
                    val linkedRecording = link.resolve("myprogram/rec.m2ts").toFile()
                    every { executedFileCommand.findByFile(linkedRecording.canonicalPath) } returns null
                    every { executedFileCommand.findByFile(linkedRecording.absolutePath) } returns executedFileOf(fixture)
                    every { programCommand.findByExecutedFileId(1) } returns program
                    every { splittedFileCommand.selectByExecutedFileId(1) } returns listOf(splittedFileOf(fixture))
                    every { programCommand.delete(any(), any()) } just Runs

                    resetRunner.run(linkedRecording.toPath(), dryRun = false)

                    verify { programCommand.delete(program, false) }
                    verify { jobs.reset(1) }
                } finally {
                    linkRoot.deleteRecursively()
                    fixture.root.deleteRecursively()
                }
            }

            expect("aborts when the executed file is not found") {
                every { executedFileCommand.findByFile(any()) } returns null

                resetRunner.run(File("/rec/missing.m2ts").toPath(), dryRun = false)

                verify(exactly = 0) { programCommand.findByExecutedFileId(any()) }
                verify(exactly = 0) { programCommand.delete(any(), any()) }
            }

            expect("aborts when the program is not found") {
                val fixture = ResetFixture()
                every { executedFileCommand.findByFile(fixture.executedFile.absolutePath) } returns executedFileOf(fixture)
                every { programCommand.findByExecutedFileId(1) } returns null

                resetRunner.run(fixture.executedFile.toPath(), dryRun = false)

                verify(exactly = 0) { splittedFileCommand.selectByExecutedFileId(any()) }
                verify(exactly = 0) { programCommand.delete(any(), any()) }
            }
        }
    })
