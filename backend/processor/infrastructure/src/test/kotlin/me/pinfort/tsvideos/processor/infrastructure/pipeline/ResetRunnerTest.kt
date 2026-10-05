package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.domain.ExecutedFile
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.SplittedFile
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
            resetRunner = ResetRunner(executedFileCommand, programCommand, splittedFileCommand, logger)
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
