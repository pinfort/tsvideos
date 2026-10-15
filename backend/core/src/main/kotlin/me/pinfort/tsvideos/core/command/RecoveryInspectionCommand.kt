package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.component.LocalArtifactInspector
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.domain.InspectionArtifact
import me.pinfort.tsvideos.core.domain.InspectionAttempt
import me.pinfort.tsvideos.core.domain.InspectionRecord
import me.pinfort.tsvideos.core.domain.InspectionStage
import me.pinfort.tsvideos.core.domain.InspectionUpload
import me.pinfort.tsvideos.core.domain.LocalCheckpointFile
import me.pinfort.tsvideos.core.domain.RecoveryInspection
import me.pinfort.tsvideos.core.domain.ResumeJobMissing
import me.pinfort.tsvideos.core.domain.VerificationStatus
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ExecutedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import me.pinfort.tsvideos.core.external.database.mapper.RecoveryInspectionMapper
import me.pinfort.tsvideos.core.external.database.mapper.SplittedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.UploadManifestMapper
import org.springframework.stereotype.Component
import java.io.File

@Component
class RecoveryInspectionCommand(
    private val selector: RecoveryInspectionMapper,
    private val recovery: ProcessingRecoveryCommand,
    private val history: ProcessingHistoryCommand,
    private val verification: LibraryVerificationCommand,
    private val local: LocalArtifactInspector,
    private val executed: ExecutedFileMapper,
    private val programs: ProgramMapper,
    private val splits: SplittedFileMapper,
    private val created: CreatedFileMapper,
    private val manifests: UploadManifestMapper,
    private val verifiedUploads: VerifiedUploadCommand,
) {
    fun inspect(
        jobId: Long? = null,
        recording: String? = null,
    ): RecoveryInspection {
        require((jobId != null) xor (recording != null))
        require(jobId == null || jobId > 0)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val path = recording?.let { File(it).canonicalPath }
        val jobs = if (jobId != null) listOf(recovery.job(jobId)) else selector.jobsByPath(checkNotNull(path))
        if (jobs.size >
            1
        ) {
            return RecoveryInspection(
                path,
                jobs.map {
                    it.id
                },
                null,
                "AMBIGUOUS",
                prerequisites = listOf("Select an explicit --job-id; multiple recording versions exist"),
            )
        }
        val job = jobs.singleOrNull()
        val rows =
            (
                listOfNotNull(
                    job?.executedFileId?.let(executed::find),
                ) + executed.selectByFile(job?.inputPath ?: checkNotNull(path))
            ).distinctBy {
                it.id
            }
        if (job == null && rows.isEmpty()) throw ResumeJobMissing()
        if (rows.size >
            1
        ) {
            return RecoveryInspection(
                path,
                emptyList(),
                null,
                "AMBIGUOUS",
                records =
                    rows.map {
                        InspectionRecord("EXECUTED", it.id, it.file, it.status.name)
                    },
                prerequisites = listOf("Multiple legacy registrations; reconcile the database manually"),
            )
        }
        val original = job?.inputPath ?: checkNotNull(path)
        var registrationInconsistent =
            job != null &&
                (
                    (job.executedFileId != null && rows.isEmpty()) ||
                        rows.any { it.id != job.executedFileId || it.file != job.inputPath || it.size != job.inputSize }
                )
        val records = mutableListOf<InspectionRecord>()
        val localPaths = linkedMapOf<String, LocalCheckpointFile?>()
        localPaths[original] = null
        val nas = mutableListOf<InspectionArtifact>()
        rows.forEach { row ->
            records.add(InspectionRecord("EXECUTED", row.id, row.file, row.status.name))
            splits.selectByExecutedFileId(row.id).forEach {
                records.add(InspectionRecord("SPLIT", it.id, it.file, it.status.name))
                localPaths[it.file] = null
            }
            created
                .selectByExecutedFileId(
                    row.id,
                ).forEach { records.add(InspectionRecord("CREATED", it.id, safeNas(it.file), it.status.name)) }
            val linkedPrograms = selector.programIdsByExecutedFileId(row.id).mapNotNull { programs.find(it) }
            if (linkedPrograms.size != 1) {
                registrationInconsistent = true
                created.selectByExecutedFileId(row.id).forEach {
                    nas.add(InspectionArtifact(safeNas(it.file), "UNCHECKED", "NONE", "PROGRAM_REGISTRATION_MISSING_OR_AMBIGUOUS"))
                }
            }
            linkedPrograms.forEach { program ->
                records.add(InspectionRecord("PROGRAM", program.id, null, program.status.name))
                verification.verify(program.id)?.findings?.forEach { finding ->
                    nas.add(
                        InspectionArtifact(
                            finding.file?.let(::safeNas) ?: "(no registered artifact)",
                            finding.status.name,
                            "SIZE_ONLY",
                            when (finding.status) {
                                VerificationStatus.VERIFIED -> "REGISTERED_SIZE_MATCH"
                                VerificationStatus.MISSING -> "ABSENT_FROM_CHECKED_STORES"
                                VerificationStatus.SIZE_MISMATCH -> "REGISTERED_SIZE_MISMATCH"
                                VerificationStatus.INACCESSIBLE -> "NAS_READ_FAILED"
                                VerificationStatus.UNCHECKED -> "NAS_NOT_VERIFIED"
                            },
                        ),
                    )
                }
            }
        }
        val evidence = job?.let { recovery.evidence(it.id) }.orEmpty()
        evidence.values.flatMap { it.files }.forEach { localPaths[it.path] = it }
        val callbackInput = job?.let { recovery.callback(it.id) }
        callbackInput?.let { callback ->
            (callback.outputs.map { it.file } + callback.cleanupFiles).forEach { localPaths[it.path] = it }
        }
        val uploads = job?.let { manifests.forJob(it.id) }.orEmpty()
        uploads.forEach { localPaths.putIfAbsent(it.sourcePath, null) }
        val observations =
            localPaths.map { (p, proof) ->
                local.inspect(
                    p,
                    proof,
                    if (p ==
                        original
                    ) {
                        job?.inputSize ?: rows.singleOrNull()?.size
                    } else {
                        null
                    },
                    if (p == original) job?.modifiedMillis else null,
                )
            }
        val plan = job?.let { recovery.plan(it.id) }
        val waiting =
            job?.state == "SUBMITTED" && plan?.stages?.all { it.reason == "WAIT_FOR_CALLBACK_OR_MANUALLY_RECONCILE_STATE" } == true
        val missingWaitingOriginal = waiting && observations.any { it.path == original && it.status == "MISSING" }
        var archiveConfirmed = false
        if (missingWaitingOriginal) {
            try {
                val archive = verifiedUploads.inspectRecovery(checkNotNull(job).id, "original")
                archiveConfirmed = archive?.state == "REGISTERED"
                if (archiveConfirmed) {
                    nas.add(
                        InspectionArtifact(
                            safeNas(checkNotNull(archive).finalPath),
                            "VERIFIED",
                            "SHA256_RECEIPT",
                            "MISSING_ORIGINAL_HAS_VERIFIED_ARCHIVE",
                        ),
                    )
                }
            } catch (failure: Exception) {
                if (ProcessingCancellation.isInterrupted(failure)) throw failure
            }
            if (!archiveConfirmed) {
                nas.add(
                    InspectionArtifact("(original archive)", "UNCHECKED", "NONE", "MISSING_ORIGINAL_ARCHIVE_NOT_VERIFIED"),
                )
            }
        }
        val stages =
            plan
                ?.stages
                ?.map { stage ->
                    InspectionStage(
                        stage.stage,
                        when {
                            stage.action == "BLOCKED" && waiting -> "WAITING"
                            stage.action == "BLOCKED" -> "INCONSISTENT"
                            stage.action == "SKIP" && stage.stage in setOf("INPUT", "CALLBACK_CONTEXT") -> "PREREQUISITE_VERIFIED"
                            stage.action == "SKIP" -> "COMPLETED"
                            else -> "INCOMPLETE"
                        },
                        stage.action,
                        stage.reason,
                        stage.prerequisites,
                    )
                }.orEmpty()
        val inconsistent =
            (missingWaitingOriginal && !archiveConfirmed) ||
                registrationInconsistent ||
                observations.any { it.status in setOf("CHANGED", "INACCESSIBLE") } ||
                nas.any { it.status in setOf("MISSING", "SIZE_MISMATCH", "INACCESSIBLE") }
        val canResume = plan != null && !plan.blocked
        return RecoveryInspection(
            original,
            jobs.map { it.id },
            job?.state,
            when {
                inconsistent -> "INCONSISTENT"
                waiting -> "WAITING"
                plan == null || plan.blocked -> "BLOCKED"
                else -> "USABLE"
            },
            records,
            observations,
            nas,
            uploads.map {
                InspectionUpload(
                    it.id,
                    it.state,
                    safeNas(it.finalPath),
                    safeNas(it.temporaryPath),
                    it.sourcePath,
                    it.claimToken != null,
                    it.createdFileId,
                    if (it.state in setOf("REGISTERED", "FINALIZED") &&
                        plan?.stages?.any { stage ->
                            stage.action != "BLOCKED" &&
                                stage.stage ==
                                (
                                    if (it.artifactKey ==
                                        me.pinfort.tsvideos.core.domain.ProcessingIdentity
                                            .hash("original")
                                    ) {
                                        "UPLOAD_ORIGINAL"
                                    } else if (it.artifactKey ==
                                        me.pinfort.tsvideos.core.domain.ProcessingIdentity
                                            .hash("encoded:${it.sourcePath}")
                                    ) {
                                        "UPLOAD_ENCODED:${it.sourcePath}"
                                    } else {
                                        "UNRECOGNIZED_ARTIFACT"
                                    }
                                )
                        } == true
                    ) {
                        "SHA256_RECEIPT"
                    } else {
                        "UNCHECKED"
                    },
                )
            },
            stages,
            job
                ?.let {
                    history.history(it.id).map { h ->
                        InspectionAttempt(
                            h.attempt.attemptNumber,
                            h.attempt.kind.name,
                            h.attempt.outcome.name,
                            h.attempt.activityAt,
                            h.attempt.errorCode?.name,
                        )
                    }
                }.orEmpty(),
            canResume,
            if (canResume) listOf("tvpcli", "resume", "--job-id", checkNotNull(job).id.toString()) else null,
            if (job ==
                null
            ) {
                listOf("Legacy registration has no durable job/checkpoints; automatic resume is unavailable")
            } else {
                stages
                    .filter {
                        it.action ==
                            "BLOCKED"
                    }.flatMap { listOf(it.reason) + it.prerequisites }
            },
            listOf(
                "Inspection and planning never change or delete original recordings or generated artifacts",
                "Reset is a manual option only after encoder/ownership reconciliation: it preserves the original and deletes generated local/NAS artifacts and registrations",
                "This is a changing snapshot, not a reservation; resume revalidates prerequisites",
            ),
            evidence,
            callbackInput?.copy(outputs = callbackInput.outputs.map { it.copy(target = safeNas(it.target)) }),
        )
    }

    private fun safeNas(path: String): String = if (path.contains("://")) "(invalid NAS path redacted)" else path
}
