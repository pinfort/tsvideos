package me.pinfort.tsvideos.core.command

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.component.LocalArtifactInspector
import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.domain.ResumePlan
import me.pinfort.tsvideos.core.domain.ResumeStagePlan
import me.pinfort.tsvideos.core.external.database.mapper.RecoveryInspectionMapper
import java.nio.file.Files

class RecoveryInspectionCommandTest :
    ExpectSpec({
        expect("missing stored recording is selected without requiring local existence and ambiguity is explicit") {
            val selector = mockk<RecoveryInspectionMapper>()
            val recovery = mockk<ProcessingRecoveryCommand>(relaxed = true)
            every { recovery.callback(any()) } returns null
            val receipts = mockk<VerifiedUploadCommand>()
            every { receipts.inspectRecovery(any(), any()) } returns null
            val path = java.io.File("missing-recording.m2ts").canonicalPath
            val job = ProcessingJob(1, "identity", path, 4, 100, "SUBMISSION_UNKNOWN", null, null)
            every { selector.jobsByPath(path) } returns listOf(job)
            every { recovery.plan(1) } returns
                ResumePlan(
                    1,
                    "BLOCKED",
                    listOf(ResumeStagePlan("SUBMIT_ENCODER", "BLOCKED", "RECONCILE_ENCODER_QUEUE_NO_AUTOMATIC_RESUBMISSION")),
                )
            val command =
                RecoveryInspectionCommand(
                    selector,
                    recovery,
                    mockk(relaxed = true),
                    mockk(),
                    LocalArtifactInspector(recovery),
                    mockk(relaxed = true),
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(relaxed = true),
                    verifiedUploads = receipts,
                )
            val report = command.inspect(recording = path)
            report.localArtifacts.single().status shouldBe "MISSING"
            report.resumePossible shouldBe false
            report.exitCode shouldBe 4
            every { selector.jobsByPath(path) } returns listOf(job, job.copy(id = 2))
            command.inspect(recording = path).candidateJobIds shouldBe listOf(1L, 2L)
            command.inspect(recording = path).status shouldBe "AMBIGUOUS"
        }
        expect("submitted wait is distinct from a blocked inconsistent owner") {
            val recovery = mockk<ProcessingRecoveryCommand>(relaxed = true)
            every { recovery.callback(any()) } returns null
            val receipts = mockk<VerifiedUploadCommand>()
            every { receipts.inspectRecovery(any(), any()) } returns null
            val file = Files.createTempFile("inspect", ".m2ts").toFile()
            try {
                val job = ProcessingJob(1, "identity", file.canonicalPath, file.length(), file.lastModified(), "SUBMITTED", null, null)
                every { recovery.job(1) } returns job
                every { recovery.plan(1) } returns
                    ResumePlan(
                        1,
                        "BLOCKED",
                        listOf(ResumeStagePlan("SUBMIT_ENCODER", "BLOCKED", "WAIT_FOR_CALLBACK_OR_MANUALLY_RECONCILE_STATE")),
                    )
                val command =
                    RecoveryInspectionCommand(
                        mockk(),
                        recovery,
                        mockk(relaxed = true),
                        mockk(),
                        LocalArtifactInspector(recovery),
                        mockk(),
                        mockk(),
                        mockk(),
                        mockk(),
                        mockk(relaxed = true),
                        verifiedUploads = receipts,
                    )
                command.inspect(1).status shouldBe "WAITING"
                command.inspect(1).exitCode shouldBe 0
                file.delete()
                command.inspect(1).status shouldBe "INCONSISTENT"
                val archive = mockk<me.pinfort.tsvideos.core.domain.UploadManifest>()
                every { archive.state } returns "REGISTERED"
                every { archive.finalPath } returns "archive.gz"
                every { receipts.inspectRecovery(1, "original") } returns archive
                command.inspect(1).status shouldBe "WAITING"
                command
                    .inspect(1)
                    .nasArtifacts
                    .single()
                    .verification shouldBe "SHA256_RECEIPT"
                every { recovery.plan(1) } returns
                    ResumePlan(1, "BLOCKED", listOf(ResumeStagePlan("OWNERSHIP", "BLOCKED", "ACTIVE_OWNER_REQUIRES_MANUAL_RECONCILIATION")))
                command.inspect(1).exitCode shouldBe 4
            } finally {
                file.delete()
            }
        }
        expect("local observations separate missing inaccessible unchecked present and changed") {
            val root = Files.createTempDirectory("inspect-local").toFile()
            val file = root.resolve("input").apply { writeText("data") }
            val inspector = LocalArtifactInspector(mockk())
            try {
                inspector.inspect(root.resolve("missing").path).status shouldBe "MISSING"
                val deniedRecovery = mockk<ProcessingRecoveryCommand>()
                every { deniedRecovery.capture(any(), any()) } throws java.nio.file.AccessDeniedException("private")
                val proof =
                    me.pinfort.tsvideos.core.domain
                        .LocalCheckpointFile(file.path, 4, file.lastModified(), "hash")
                LocalArtifactInspector(deniedRecovery).inspect(file.path, proof).status shouldBe "INACCESSIBLE"
                inspector.inspect(root.path).status shouldBe "UNCHECKED"
                inspector.inspect(file.path).status shouldBe "PRESENT"
                inspector.inspect(file.path, size = 99).status shouldBe "CHANGED"
            } finally {
                root.deleteRecursively()
            }
        }
    })
