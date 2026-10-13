package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ProcessingJobCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.command.VerifiedUploadCommand
import me.pinfort.tsvideos.core.component.MimeTypeComponent
import me.pinfort.tsvideos.core.component.NasDestinationResolver
import me.pinfort.tsvideos.core.component.NormalizeComponent
import me.pinfort.tsvideos.core.component.ValidateCompletedComponent
import me.pinfort.tsvideos.core.domain.ExecutedFile
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.SplittedFile
import me.pinfort.tsvideos.core.domain.VerifiedUploadReceipt
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.processor.infrastructure.external.slack.SlackClient
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class AfterEncodeRunnerTest :
    ExpectSpec({
        class Fixture {
            val root = Files.createTempDirectory("after-encode-").toFile()
            val recording = File(root, "myprogram").apply { mkdirs() }
            val splitDirectory = File(recording, "tssplitter").apply { mkdirs() }
            val output = File(File(splitDirectory, "encoded").apply { mkdirs() }, "video.mp4").apply { writeText("video") }
            val original = File(recording, "raw.m2ts").apply { writeText("original") }
            val split = File(splitDirectory, "split.m2ts").apply { writeText("split") }
            val input = File(File(splitDirectory, "succeeded").apply { mkdirs() }, "split.m2ts").apply { writeText("split") }
            val splits = mockk<SplittedFileCommand>(relaxed = true)
            val executed = mockk<ExecutedFileCommand>(relaxed = true)
            val programs = mockk<ProgramCommand>(relaxed = true)
            val jobs = mockk<ProcessingJobCommand>(relaxed = true)
            val uploads = mockk<VerifiedUploadCommand>(relaxed = true)
            val history = historyFixture()
            val validation = mockk<ValidateCompletedComponent>()
            val slack = mockk<SlackClient>(relaxed = true)
            val samba = mockk<SambaClient>()
            val identity = ProcessingIdentity.from(original)
            val job = ProcessingJob(1, identity.key, identity.path, identity.size, identity.modifiedMillis, "SUBMITTED", 10, null)
            val splitRow = SplittedFile(20, 10, split.canonicalPath, split.length(), 1.0, SplittedFile.Status.COMPRESS_SAVED)
            val program = Program(1, "raw", 10, Program.Status.REGISTERED, 0, 8, LocalDateTime.now(), "ch", "title", "name", 1.0)
            val receipt = VerifiedUploadReceipt(123)
            val runner: AfterEncodeRunner

            init {
                every { splits.findByFile(split.canonicalPath) } returns splitRow
                every { splits.selectByExecutedFileId(10) } returns listOf(splitRow)
                every { executed.find(10) } returns
                    ExecutedFile(
                        10,
                        original.canonicalPath,
                        0,
                        original.length(),
                        LocalDateTime.now(),
                        "ch",
                        "title",
                        "name",
                        1.0,
                        ExecutedFile.Status.SPLITTED,
                    )
                every { programs.findByExecutedFileId(10) } returns program
                every { jobs.findByExecutedFile(10) } returns job
                every { jobs.claimCallback(any()) } returns "token"
                every { samba.resolvePathUnderBaseDir(any(), any()) } answers { "base/${secondArg<String>()}" }
                every { validation.validate(1) } returns true
                every { uploads.upload(any(), any(), any(), any(), any(), any()) } returns receipt
                every { uploads.deleteLocal(receipt) } answers {
                    output.delete()
                    Unit
                }
                runner =
                    AfterEncodeRunner(
                        splits,
                        executed,
                        programs,
                        NasDestinationResolver(NormalizeComponent(), samba),
                        MimeTypeComponent(),
                        validation,
                        slack,
                        mockk(relaxed = true),
                        jobs,
                        uploads,
                        history,
                    )
            }

            fun run(
                success: Boolean = true,
                dryRun: Boolean = false,
                files: List<File> = listOf(output),
            ) = runner.run(AfterEncodeRunner.Input(1, input.toPath(), files.map { it.toPath() }, success, "encoder failed"), dryRun)

            fun cleanup() {
                root.deleteRecursively()
            }
        }
        expect("exhausted upload failure retains notification and release failures without another Slack attempt") {
            val f = Fixture()
            val primary = java.net.SocketTimeoutException("exhausted")
            val notification = IllegalStateException("notification")
            val release = IllegalStateException("release")
            every { f.uploads.upload(any(), any(), any(), any(), any(), any()) } throws primary
            every { f.slack.notify(any()) } throws notification
            every { f.jobs.releaseCallback(any(), any()) } throws release
            try {
                shouldThrow<java.net.SocketTimeoutException> { f.run() } shouldBe primary
                primary.suppressed.toList() shouldBe listOf(notification, release)
                verify(exactly = 1) { f.slack.notify(any()) }
                verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.ERROR, false) }
                f.original.exists() shouldBe true
                f.output.exists() shouldBe true
            } finally {
                f.cleanup()
            }
        }
        expect("callback interruption propagates, preserves originals and releases ownership") {
            val f = Fixture()
            every { f.uploads.upload(any(), any(), any(), any(), any(), any()) } throws InterruptedException()
            try {
                io.kotest.assertions.throwables
                    .shouldThrow<InterruptedException> { f.run() }
                f.original.exists() shouldBe true
                f.output.exists() shouldBe true
                verify { f.history.failed(any(), any()) }
                verify { f.jobs.releaseCallback(f.job, "token") }
                verify(exactly = 0) { f.uploads.deleteLocal(any()) }
                verify(exactly = 0) { f.slack.notify(any()) }
            } finally {
                f.cleanup()
            }
        }
        expect("verified receipt is registered before output deletion and original archive checked before completion") {
            val f = Fixture()
            try {
                f.run()
                verifyOrder {
                    f.uploads.upload(
                        1,
                        "encoded:${f.output.canonicalPath}",
                        f.output,
                        SambaClient.NasType.VIDEO_STORE_NAS,
                        "base/m/myprogram/video.mp4",
                        any(),
                    )
                    f.uploads.register(f.receipt, 20, "video/mp4", null)
                    f.uploads.deleteLocal(f.receipt)
                    f.uploads.verifyRegistered(1, "original")
                    f.jobs.complete(f.job)
                }
                verify { f.history.finish(44, me.pinfort.tsvideos.core.domain.ProcessingOutcome.COMPLETED, null) }
                f.output.exists() shouldBe false
                f.original.exists() shouldBe false
                f.input.exists() shouldBe false
                f.split.exists() shouldBe false
                verify(exactly = 0) { f.slack.notify(any()) }
            } finally {
                f.cleanup()
            }
        }
        listOf("upload", "register", "delete").forEach { stage ->
            expect("$stage failure preserves original and retry uses the same durable artifact key") {
                val f = Fixture()
                try {
                    when (stage) {
                        "upload" ->
                            every { f.uploads.upload(any(), any(), any(), any(), any(), any()) } throws
                                IllegalStateException("upload failure")
                        "register" -> every { f.uploads.register(any(), any(), any(), any()) } throws IllegalStateException("db failure")
                        else -> every { f.uploads.deleteLocal(any()) } throws IllegalStateException("delete failure")
                    }
                    shouldThrow<IllegalStateException> { f.run() }
                    verify(exactly = 1) { f.slack.notify(any()) }
                    f.original.exists() shouldBe true
                    f.output.exists() shouldBe true
                    verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.ERROR, false) }
                    verify { f.jobs.releaseCallback(f.job, "token") }
                    verify { f.history.failed(44, any()) }
                    if (stage != "delete") verify(exactly = 0) { f.uploads.deleteLocal(any()) }
                } finally {
                    f.cleanup()
                }
            }
        }
        expect("repeated callbacks pass deleted outputs to durable upload reconciliation") {
            val f = Fixture()
            try {
                f.run()
                f.run()
                verify(exactly = 2) { f.uploads.upload(1, "encoded:${f.output.canonicalPath}", f.output, any(), any(), any()) }
                verify(exactly = 2) { f.jobs.releaseCallback(f.job, "token") }
            } finally {
                f.cleanup()
            }
        }
        listOf("receipt", "changed", "validation", "validation-exception").forEach { reason ->
            expect("$reason failure preserves raw and split recordings") {
                val f = Fixture()
                try {
                    when (reason) {
                        "receipt" ->
                            every { f.uploads.verifyRegistered(1, "original") } throws
                                IllegalStateException("legacy receipt missing")
                        "changed" -> f.original.appendText("changed")
                        "validation" -> every { f.validation.validate(1) } returns false
                        else -> every { f.validation.validate(1) } throws IllegalStateException("unavailable")
                    }
                    shouldThrow<IllegalStateException> { f.run() }
                    verify(exactly = 1) { f.slack.notify(any()) }
                    f.original.exists() shouldBe true
                    f.input.exists() shouldBe true
                    f.split.exists() shouldBe true
                    verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.ERROR, false) }
                    verify(exactly = 0) { f.jobs.complete(any()) }
                } finally {
                    f.cleanup()
                }
            }
        }
        expect("missing program reports failure without removing raw recording") {
            val f = Fixture()
            try {
                every { f.programs.findByExecutedFileId(10) } returns null
                shouldThrow<IllegalStateException> { f.run() }
                f.original.exists() shouldBe true
                verify { f.slack.notify(match { it.contains("program not found") }) }
            } finally {
                f.cleanup()
            }
        }
        expect("failed encoder callback touches no uploads") {
            val f = Fixture()
            try {
                f.run(success = false)
                f.output.exists() shouldBe true
                verify(exactly = 0) { f.uploads.upload(any(), any(), any(), any(), any(), any()) }
                verify { f.slack.notify(match { it.contains("encode failed") }) }
            } finally {
                f.cleanup()
            }
        }
        expect("missing split aborts before claiming or uploading") {
            val f = Fixture()
            try {
                every { f.splits.findByFile(any()) } returns null
                f.run()
                verify(exactly = 0) { f.jobs.claimCallback(any()) }
                verify(exactly = 0) { f.history.begin(any(), any()) }
                verify(exactly = 0) { f.uploads.upload(any(), any(), any(), any(), any(), any()) }
                f.output.exists() shouldBe true
            } finally {
                f.cleanup()
            }
        }
        expect("duplicate and empty output manifests cannot upload") {
            val f = Fixture()
            try {
                shouldThrow<IllegalArgumentException> { f.run(files = emptyList()) }
                shouldThrow<IllegalArgumentException> { f.run(files = listOf(f.output, f.output)) }
                verify(exactly = 0) { f.uploads.upload(any(), any(), any(), any(), any(), any()) }
                f.output.exists() shouldBe true
            } finally {
                f.cleanup()
            }
        }
        expect("dry run keeps every local file and makes no upload or claim mutation") {
            val f = Fixture()
            try {
                f.run(dryRun = true)
                listOf(f.output, f.original, f.input, f.split).forEach { it.exists() shouldBe true }
                verify(exactly = 0) { f.uploads.upload(any(), any(), any(), any(), any(), any()) }
                verify(exactly = 0) { f.jobs.claimCallback(any()) }
                verify(exactly = 0) { f.history.begin(any(), any()) }
            } finally {
                f.cleanup()
            }
        }
    })
