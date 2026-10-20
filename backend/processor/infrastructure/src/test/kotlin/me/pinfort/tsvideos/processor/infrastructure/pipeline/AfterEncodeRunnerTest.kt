package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import me.pinfort.tsvideos.core.domain.VideoResolution
import me.pinfort.tsvideos.core.external.tool.VideoResolutionProbeClient
import me.pinfort.tsvideos.processor.infrastructure.external.slack.SlackClient
import me.pinfort.tsvideos.storage.api.NasPaths
import me.pinfort.tsvideos.storage.api.NasStore
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

class AfterEncodeRunnerTest :
    ExpectSpec({
        class Fixture(
            directoryName: String = "myprogram",
        ) {
            val root = Files.createTempDirectory("after-encode-").toFile()
            val recording = File(root, directoryName).apply { mkdirs() }
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
            val logger = mockk<Logger>(relaxed = true)
            val nasPaths = mockk<NasPaths>()
            val resolutionProbe = mockk<VideoResolutionProbeClient>()
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
                every { nasPaths.resolvePathUnderBaseDir(any(), any()) } answers { "base/${secondArg<String>()}" }
                every { validation.validate(1) } returns true
                every { resolutionProbe.probe(any()) } returns VideoResolution(1920, 1080)
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
                        NasDestinationResolver(NormalizeComponent(), nasPaths),
                        MimeTypeComponent(),
                        validation,
                        slack,
                        logger,
                        jobs,
                        uploads,
                        history,
                        resolutionProbe,
                    )
            }

            fun run(
                success: Boolean = true,
                dryRun: Boolean = false,
                files: List<File> = listOf(output),
                inPath: Path = input.toPath(),
            ) = runner.run(AfterEncodeRunner.Input(1, inPath, files.map { it.toPath() }, success, "encoder failed"), dryRun)

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
        expect("all rejected dry-run callbacks avoid Slack and database mutation") {
            val f = Fixture()
            try {
                shouldThrow<IllegalStateException> { f.run(success = false, dryRun = true) }
                every { f.validation.validate(1) } returns false
                shouldThrow<IllegalStateException> { f.run(dryRun = true) }
                every { f.splits.findByFile(any()) } returns null
                shouldThrow<IllegalStateException> { f.run(dryRun = true) }
                verify(exactly = 0) { f.slack.notify(any()) }
                verify(exactly = 0) { f.history.begin(any(), any()) }
                verify(exactly = 0) { f.jobs.claimCallback(any()) }
                verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.ERROR, true) }
                f.original.exists() shouldBe true
            } finally {
                f.cleanup()
            }
        }
        expect("interrupted failure reporting retains primary and restores cancellation flag") {
            val f = Fixture()
            val primary = IllegalStateException("permanent failure")
            val interrupted = InterruptedException("notification interrupted")
            every { f.uploads.upload(any(), any(), any(), any(), any(), any()) } throws primary
            every { f.slack.notify(any()) } throws interrupted
            try {
                shouldThrow<IllegalStateException> { f.run() } shouldBe primary
                primary.suppressed.single() shouldBe interrupted
                Thread.currentThread().isInterrupted shouldBe true
                me.pinfort.tsvideos.core.component.ProcessingCancellation
                    .isInterrupted(primary) shouldBe true
                verify(exactly = 1) { f.slack.notify(any()) }
                f.original.exists() shouldBe true
            } finally {
                Thread.interrupted()
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
        expect("non-video outputs are registered without probing a resolution") {
            val f = Fixture()
            val subtitle = File(f.output.parentFile, "video.ass").apply { writeText("subtitle") }
            try {
                f.run(files = listOf(f.output, subtitle))
                verify(exactly = 1) { f.resolutionProbe.probe(f.output) }
                verify(exactly = 0) { f.resolutionProbe.probe(subtitle) }
                verify { f.uploads.register(f.receipt, 20, "video/mp4", null, VideoResolution(1920, 1080)) }
                verify { f.uploads.register(f.receipt, 20, "text/x-ssa", null, null) }
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
                        NasStore.VIDEO_STORE_NAS,
                        "base/m/myprogram/video.mp4",
                        any(),
                    )
                    f.uploads.register(f.receipt, 20, "video/mp4", null, VideoResolution(1920, 1080))
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
                        "register" ->
                            every { f.uploads.register(any(), any(), any(), any(), any()) } throws
                                IllegalStateException("db failure")
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
                shouldThrow<IllegalStateException> { f.run(success = false) }
                f.output.exists() shouldBe true
                verify(exactly = 0) { f.uploads.upload(any(), any(), any(), any(), any(), any()) }
                verify { f.slack.notify(match { it.contains("encode failed") }) }
            } finally {
                f.cleanup()
            }
        }
        expect("invalid program fails the callback with its ID and marks it ERROR, dryRun=false") {
            val f = Fixture()
            try {
                every { f.validation.validate(1) } returns false
                shouldThrow<IllegalStateException> { f.run() }
                verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.ERROR, false) }
                verify { f.slack.notify(match { it.contains("program invalid. programId:1") }) }
                f.original.exists() shouldBe true
            } finally {
                f.cleanup()
            }
        }
        expect("dry run reports an invalid program without touching the program or files") {
            val f = Fixture()
            try {
                every { f.validation.validate(1) } returns false
                shouldThrow<IllegalStateException> { f.run(dryRun = true) }.message shouldBe "program invalid. programId:1"
                verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.ERROR, true) }
                verify(exactly = 0) { f.programs.updateStatusByExecutedFileId(any(), any(), false) }
                verify(exactly = 0) { f.slack.notify(any()) }
                listOf(f.output, f.original, f.input, f.split).forEach { it.exists() shouldBe true }
            } finally {
                f.cleanup()
            }
        }
        expect("failed encoder callback still notifies when recording its history fails") {
            val f = Fixture()
            try {
                every { f.history.begin(any(), any()) } throws IllegalStateException("History identity conflict")
                shouldThrow<IllegalStateException> { f.run(success = false) }
                verify { f.slack.notify(match { it.contains("encode failed") }) }
            } finally {
                f.cleanup()
            }
        }
        expect("missing split aborts before claiming or uploading") {
            val f = Fixture()
            try {
                every { f.splits.findByFile(any()) } returns null
                shouldThrow<IllegalStateException> { f.run() }
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
                verify { f.logger.info(match<String> { it.contains("base/m/myprogram/video.mp4") }) }
                verify(exactly = 0) { f.slack.notify(any()) }
            } finally {
                f.cleanup()
            }
        }
        expect("dry run reports a missing callback output without uploading") {
            val f = Fixture()
            try {
                f.output.delete()
                shouldThrow<IllegalStateException> { f.run(dryRun = true) }.message shouldContain "Callback output missing"
                verify(exactly = 0) { f.slack.notify(any()) }
                verify(exactly = 0) { f.uploads.upload(any(), any(), any(), any(), any(), any()) }
                listOf(f.original, f.input, f.split).forEach { it.exists() shouldBe true }
            } finally {
                f.cleanup()
            }
        }
        expect("uploads under the normalized recording directory") {
            val f = Fixture("Ａnime:０１")
            try {
                f.run()
                verify { f.uploads.upload(1, any(), f.output, NasStore.VIDEO_STORE_NAS, "base/A/Anime：01/video.mp4", any()) }
            } finally {
                f.cleanup()
            }
        }
        listOf<Pair<String, (Fixture) -> Unit>>(
            "legacy callback" to { f -> every { f.jobs.findByExecutedFile(10) } returns null },
            "callback already claimed" to { f -> every { f.jobs.claimCallback(any()) } returns null },
            "claim query fails" to { f -> every { f.jobs.claimCallback(any()) } throws RuntimeException("db down") },
        ).forEach { (case, stub) ->
            expect("claim failure notifies without touching the program or uploads, case=$case") {
                val f = Fixture()
                try {
                    stub(f)
                    shouldThrow<Exception> { f.run() }
                    verify { f.slack.notify(match { it.contains("claiming encode callback failed") }) }
                    verify(exactly = 0) { f.programs.updateStatusByExecutedFileId(any(), any(), any()) }
                    verify(exactly = 0) { f.uploads.upload(any(), any(), any(), any(), any(), any()) }
                    verify(exactly = 0) { f.jobs.releaseCallback(any(), any()) }
                    f.output.exists() shouldBe true
                } finally {
                    f.cleanup()
                }
            }
        }
        expect("names the current claim and its age when the callback is already claimed") {
            val f = Fixture()
            try {
                every { f.jobs.findByExecutedFile(10) } returns
                    f.job.copy(callbackClaim = "other-owner", updatedAt = LocalDateTime.of(2026, 10, 1, 3, 4))
                every { f.jobs.claimCallback(any()) } returns null
                shouldThrow<IllegalStateException> { f.run() }
                verify { f.slack.notify(match { it.contains("other-owner") && it.contains("2026-10-01T03:04") }) }
            } finally {
                f.cleanup()
            }
        }
        expect("a failed claim release after success is reported without undoing the completion") {
            val f = Fixture()
            try {
                every { f.jobs.releaseCallback(any(), "token") } throws IllegalStateException("claim lost")
                shouldThrow<IllegalStateException> { f.run() }.message shouldBe "claim lost"
                verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.COMPLETED, false) }
                verify(exactly = 0) { f.programs.updateStatusByExecutedFileId(10, Program.Status.ERROR, any()) }
                verify { f.slack.notify(match { it.contains("releasing callback claim of job 1 failed") }) }
            } finally {
                f.cleanup()
            }
        }
        expect("a failed claim release does not mask the processing failure") {
            val f = Fixture()
            try {
                every { f.uploads.upload(any(), any(), any(), any(), any(), any()) } throws RuntimeException("smb timeout")
                every { f.jobs.releaseCallback(any(), "token") } throws IllegalStateException("claim lost")
                val failure = shouldThrow<RuntimeException> { f.run() }
                failure.message shouldBe "smb timeout"
                failure.suppressed.map { it.message } shouldBe listOf("claim lost")
                verify { f.slack.notify(match { it.contains("smb timeout") }) }
                verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.ERROR, false) }
            } finally {
                f.cleanup()
            }
        }
        expect("completes a job whose submission acknowledgement has not been recorded yet") {
            val f = Fixture()
            try {
                every { f.jobs.findByExecutedFile(10) } returns f.job.copy(state = "SUBMISSION_UNKNOWN")
                f.run()
                verify { f.jobs.complete(match { it.state == "SUBMISSION_UNKNOWN" }) }
                verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.COMPLETED, false) }
            } finally {
                f.cleanup()
            }
        }
        expect("marks the program as ERROR when completion fails after the originals were removed") {
            val f = Fixture()
            try {
                every { f.programs.updateStatusByExecutedFileId(10, Program.Status.COMPLETED, false) } throws RuntimeException("db down")
                shouldThrow<RuntimeException> { f.run() }
                f.input.exists() shouldBe false
                f.original.exists() shouldBe false
                verify { f.slack.notify(match { it.contains("db down") }) }
                verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.ERROR, false) }
                verify(exactly = 0) { f.jobs.complete(any()) }
                verify(exactly = 0) { f.logger.info(match<String> { it.contains("uploading file completed") }) }
                verify { f.jobs.releaseCallback(any(), "token") }
            } finally {
                f.cleanup()
            }
        }
        expect("keeps a completed program when recording the job completion fails") {
            val f = Fixture()
            try {
                every { f.jobs.complete(any()) } throws IllegalStateException("job is RESETTING")
                f.run()
                verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.COMPLETED, false) }
                verify(exactly = 0) { f.programs.updateStatusByExecutedFileId(10, Program.Status.ERROR, any()) }
                verify { f.slack.notify(match { it.contains("marking processing job completed failed") }) }
            } finally {
                f.cleanup()
            }
        }
        expect("keeps a completed program when recording the completed attempt fails") {
            val f = Fixture()
            try {
                every { f.history.finish(44, any(), any()) } throws IllegalStateException("Attempt is not active")
                f.run()
                verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.COMPLETED, false) }
                verify(exactly = 0) { f.programs.updateStatusByExecutedFileId(10, Program.Status.ERROR, any()) }
                verify(exactly = 0) { f.history.failed(any(), any()) }
                verify { f.slack.notify(match { it.contains("program stays COMPLETED") }) }
            } finally {
                f.cleanup()
            }
        }
        expect("a failing replay of a completed job keeps the existing outcome") {
            val f = Fixture()
            try {
                every { f.jobs.findByExecutedFile(10) } returns f.job.copy(state = "COMPLETED")
                every { f.uploads.upload(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("source gone")
                shouldThrow<IllegalStateException> { f.run() }
                verify(exactly = 0) { f.programs.updateStatusByExecutedFileId(any(), any(), any()) }
                verify { f.jobs.releaseCallback(any(), "token") }
            } finally {
                f.cleanup()
            }
        }
        expect("finds a pre-064 splitted file registered through a symlinked path") {
            val f = Fixture()
            val linkRoot = Files.createTempDirectory("after-encode-link").toFile()
            try {
                val link = Files.createSymbolicLink(File(linkRoot, "link").toPath(), f.root.toPath())
                val linkedSplit = link.resolve("myprogram/tssplitter/split.m2ts").toFile()
                every { f.splits.findByFile(linkedSplit.canonicalPath) } returns null
                every { f.splits.findByFile(linkedSplit.absolutePath) } returns f.splitRow
                f.run(inPath = link.resolve("myprogram/tssplitter/succeeded/split.m2ts"))
                verify { f.splits.findByFile(linkedSplit.absolutePath) }
                verify { f.programs.updateStatusByExecutedFileId(10, Program.Status.COMPLETED, false) }
            } finally {
                linkRoot.deleteRecursively()
                f.cleanup()
            }
        }
    })
