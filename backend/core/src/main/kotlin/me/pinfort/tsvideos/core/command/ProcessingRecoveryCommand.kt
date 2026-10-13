package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.component.ProcessingRecoveryJson
import me.pinfort.tsvideos.core.domain.CallbackInputManifest
import me.pinfort.tsvideos.core.domain.CheckpointEvidence
import me.pinfort.tsvideos.core.domain.LocalCheckpointFile
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.domain.ResumeBlocked
import me.pinfort.tsvideos.core.domain.ResumeJobMissing
import me.pinfort.tsvideos.core.domain.ResumePlan
import me.pinfort.tsvideos.core.domain.ResumeStagePlan
import me.pinfort.tsvideos.core.external.database.mapper.ExecutedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingJobMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingRecoveryMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import me.pinfort.tsvideos.core.external.database.mapper.SplittedFileMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

@Component
class ProcessingRecoveryCommand(
    private val mapper: ProcessingRecoveryMapper,
    private val jobs: ProcessingJobMapper,
    private val executed: ExecutedFileMapper,
    private val splits: SplittedFileMapper,
    private val programs: ProgramMapper,
    private val uploads: VerifiedUploadCommand,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior =
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    fun job(id: Long): ProcessingJob = mapper.job(id) ?: throw ResumeJobMissing()

    fun evidence(id: Long): Map<String, CheckpointEvidence> =
        mapper.checkpoints(id).associate {
            it.stage to
                ProcessingRecoveryJson.evidence(it.evidence)
        }

    fun callback(id: Long): CallbackInputManifest? = mapper.callback(id)?.let(ProcessingRecoveryJson::callback)

    fun acquire(
        id: Long,
        resume: Boolean,
    ): String =
        checkNotNull(
            transaction.execute {
                mapper.lockJob(id) ?: throw ResumeJobMissing()
                if (resume &&
                    (mapper.active(id) != 0 || mapper.attempts(id) == 0)
                ) {
                    throw ResumeBlocked("ACTIVE_OR_UNRECORDED_OWNER_REQUIRES_RECONCILIATION")
                }
                val token = UUID.randomUUID().toString()
                if (mapper.claim(id, token) != 1) throw ResumeBlocked("PROCESSING_OWNER_OR_STATE_REQUIRES_RECONCILIATION")
                token
            },
        )

    fun acquireCallback(id: Long): String =
        checkNotNull(
            transaction.execute {
                mapper.lockJob(id) ?: throw ResumeJobMissing()
                if (mapper.active(id) != 0 || mapper.owner(id) != null) throw ResumeBlocked("ACTIVE_OWNER_REQUIRES_RECONCILIATION")
                val token = UUID.randomUUID().toString()
                if (jobs.claimCallback(id, token) != 1) throw ResumeBlocked("CALLBACK_OWNER_REQUIRES_RECONCILIATION")
                token
            },
        )

    /** A failed history finalization retains its owner; age never proves the old worker dead. */
    fun release(
        id: Long,
        token: String,
    ) {
        transaction.execute {
            mapper.lockJob(id) ?: return@execute
            if (mapper.active(id) == 0) mapper.release(id, token)
        }
    }

    fun releaseCallback(
        id: Long,
        token: String,
    ) {
        transaction.execute {
            mapper.lockJob(id) ?: return@execute
            if (mapper.activeCallback(id) == 0) jobs.releaseCallback(id, token)
        }
    }

    fun save(
        id: Long,
        attemptId: Long,
        token: String,
        stage: String,
        evidence: CheckpointEvidence,
    ) {
        transaction.execute {
            val job = mapper.lockJob(id) ?: throw ResumeJobMissing()
            check(mapper.owner(id) == token || job.callbackClaim == token) { "Checkpoint ownership lost" }
            check(mapper.activeAttempt(id, attemptId) == 1) { "Checkpoint attempt is not active" }
            mapper.checkpoint(id, attemptId, stage, ProcessingRecoveryJson.write(evidence))
        }
    }

    fun invalidate(
        id: Long,
        token: String,
    ) {
        transaction.execute {
            mapper.lockJob(id) ?: throw ResumeJobMissing()
            check(mapper.owner(id) == token)
            mapper.invalidate(id)
        }
    }

    fun freezeCallback(
        manifest: CallbackInputManifest,
        token: String,
    ): CallbackInputManifest =
        checkNotNull(
            transaction.execute {
                val job = mapper.lockJob(manifest.jobId) ?: throw ResumeJobMissing()
                check(job.callbackClaim == token)
                val expected =
                    ProcessingIdentity.hash(
                        "${manifest.itemId}\u0000${manifest.outputs.map { it.file.path }.sorted().joinToString("\u0000")}",
                    )
                check(job.callbackManifest == null || job.callbackManifest == expected) { "Callback manifest identity changed" }
                val old = callback(manifest.jobId)
                if (old ==
                    null
                ) {
                    mapper.callbackInput(manifest.jobId, ProcessingRecoveryJson.write(manifest))
                } else {
                    check(old == manifest) { "Callback input changed" }
                }
                old ?: manifest
            },
        )

    fun present(file: File): Boolean =
        try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            true
        } catch (_: NoSuchFileException) {
            false
        }

    fun capture(
        file: File,
        rowId: Long? = null,
    ): LocalCheckpointFile {
        val path = file.canonicalFile.toPath()
        val before = Files.readAttributes(path, BasicFileAttributes::class.java)
        require(before.isRegularFile) { "Artifact is not a regular file" }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Checkpoint hashing interrupted")
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java)
        check(
            before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey(),
        ) {
            "LOCAL_ARTIFACT_CHANGED"
        }
        return LocalCheckpointFile(
            path.toString(),
            before.size(),
            before.lastModifiedTime().toMillis(),
            digest.digest().joinToString("") {
                "%02x".format(it)
            },
            rowId,
        )
    }

    fun verify(file: LocalCheckpointFile) {
        check(capture(File(file.path), file.rowId) == file) { "LOCAL_ARTIFACT_CHANGED" }
    }

    fun plan(
        id: Long,
        ownedToken: String? = null,
    ): ResumePlan {
        require(id > 0)
        val job = job(id)
        val stages = mutableListOf<ResumeStagePlan>()

        fun blocked(
            stage: String,
            reason: String,
        ) {
            stages.add(
                ResumeStagePlan(
                    stage,
                    "BLOCKED",
                    reason,
                    listOf("Inspect evidence and reconcile ownership or restore the unchanged artifact; do not blindly retry"),
                ),
            )
        }
        if ((mapper.owner(id) != null && mapper.owner(id) != ownedToken) ||
            (job.callbackClaim != null && job.callbackClaim != ownedToken) ||
            mapper.active(id) != 0
        ) {
            blocked("OWNERSHIP", "ACTIVE_OWNER_REQUIRES_MANUAL_RECONCILIATION")
            return ResumePlan(id, "BLOCKED", stages)
        }
        val callback = callback(id)
        if (callback != null && job.state in setOf("SUBMITTED", "SUBMISSION_UNKNOWN", "COMPLETED")) {
            checked(stages, "CALLBACK_CONTEXT") {
                val executedId = checkNotNull(job.executedFileId) { "CALLBACK_REGISTRATION_MISSING" }
                check(splits.selectByExecutedFileId(executedId).any { it.id == callback.splitId }) { "CALLBACK_REGISTRATION_MISSING" }
                check(programs.selectAll().count { it.executedFileId == executedId } == 1) { "CALLBACK_PROGRAM_MISSING" }
                "SKIP"
            }
            for (output in callback.outputs) {
                checked(stages, "UPLOAD_ENCODED:${output.file.path}") {
                    val manifest = uploads.inspectRecovery(id, "encoded:${output.file.path}")
                    if (manifest == null) {
                        verify(output.file)
                    } else {
                        check(
                            manifest.sourceSha256 == output.file.sha256 &&
                                manifest.sourceSize == output.file.size &&
                                manifest.finalPath == output.target,
                        ) { "CALLBACK_ARTIFACT_CHANGED" }
                        if (manifest.state !in setOf("REGISTERED", "FINALIZED")) verify(output.file)
                    }
                    if (manifest?.state == "REGISTERED" && !present(File(output.file.path))) "SKIP" else "EXECUTE"
                }
            }
            checked(stages, "FINALIZE") {
                check(uploads.inspectRecovery(id, "original")?.state == "REGISTERED") { "ARCHIVE_RECEIPT_MISSING" }
                callback.cleanupFiles.forEach { if (present(File(it.path))) verify(it) }
                if (present(File(job.inputPath))) {
                    check(ProcessingIdentity.from(File(job.inputPath)).key == job.identityKey) { "ORIGINAL_CHANGED" }
                }
                if (job.state == "COMPLETED" &&
                    evidence(id).containsKey("CALLBACK_COMPLETE") &&
                    !present(File(job.inputPath)) &&
                    callback.cleanupFiles.none { present(File(it.path)) }
                ) {
                    "SKIP"
                } else {
                    "EXECUTE"
                }
            }
            return ResumePlan(id, "CALLBACK", stages)
        }
        if (job.state !in setOf("CLAIMED", "RETRYABLE")) {
            blocked(
                "SUBMIT_ENCODER",
                if (job.state ==
                    "SUBMISSION_UNKNOWN"
                ) {
                    "RECONCILE_ENCODER_QUEUE_NO_AUTOMATIC_RESUBMISSION"
                } else {
                    "WAIT_FOR_CALLBACK_OR_MANUALLY_RECONCILE_STATE"
                },
            )
            return ResumePlan(id, "BLOCKED", stages)
        }
        if (mapper.attempts(id) == 0) blocked("OWNERSHIP", "NO_PRIOR_TERMINAL_ATTEMPT")
        checked(stages, "INPUT") {
            if (!present(File(job.inputPath))) throw NoSuchFileException(job.inputPath)
            check(ProcessingIdentity.from(File(job.inputPath)).key == job.identityKey) { "ORIGINAL_CHANGED" }
            "SKIP"
        }
        val evidence = evidence(id)
        checked(stages, "INSPECT") { if (evidence["INSPECT"] == null) "EXECUTE" else "SKIP" }
        checked(stages, "REGISTER") {
            val register = evidence["REGISTER"]
            if (register == null) {
                check(job.executedFileId == null || executed.find(job.executedFileId) == null) { "REGISTRATION_WITHOUT_CHECKPOINT" }
                "EXECUTE"
            } else {
                val row = executed.find(checkNotNull(register.referenceId)) ?: error("REGISTRATION_MISSING")
                check(job.executedFileId == row.id && row.file == job.inputPath && row.size == job.inputSize) { "REGISTRATION_CHANGED" }
                val registeredPrograms = programs.selectAll().filter { it.executedFileId == row.id }
                check(
                    registeredPrograms.size == 1 && registeredPrograms.single().name == File(job.inputPath).name,
                ) { "PROGRAM_REGISTRATION_CHANGED" }
                val name =
                    me.pinfort.tsvideos.core.domain.FileName
                        .fromFileNameString(File(job.inputPath).name)
                check(
                    row.channel == name.channel &&
                        row.channelName == name.channelName &&
                        row.title == name.title &&
                        row.recordedAt == name.recordedAt &&
                        row.drops == evidence["INSPECT"]?.drops &&
                        row.duration == evidence["INSPECT"]?.duration,
                ) { "REGISTRATION_CHANGED" }
                "SKIP"
            }
        }
        checked(stages, "SPLIT") {
            val split = evidence["SPLIT"]
            if (split == null) {
                check(
                    job.executedFileId == null || splits.selectByExecutedFileId(job.executedFileId).isEmpty(),
                ) { "SPLIT_WITHOUT_CHECKPOINT" }
                val original = File(job.inputPath)
                val directory = File(original.parentFile, "tssplitter")
                if (present(directory)) {
                    val files = directory.listFiles() ?: throw java.io.IOException("Split directory inaccessible")
                    check(
                        files.none { it.name.startsWith(original.nameWithoutExtension) && it.name.endsWith(".m2ts") },
                    ) { "UNOWNED_SPLIT_OUTPUTS" }
                }
                "EXECUTE"
            } else {
                split.files.forEach(::verify)
                val rows = splits.selectByExecutedFileId(checkNotNull(job.executedFileId))
                check(rows.map { it.id }.sorted() == split.files.map { checkNotNull(it.rowId) }.sorted()) { "SPLIT_REGISTRATION_CHANGED" }
                split.files.forEach { file ->
                    check(rows.any { it.id == file.rowId && it.file == file.path && it.size == file.size }) { "SPLIT_REGISTRATION_CHANGED" }
                }
                check(split.referenceId in rows.map { it.id })
                "SKIP"
            }
        }
        var archived = false
        checked(stages, "UPLOAD_ORIGINAL") {
            val manifest = uploads.inspectRecovery(id, "original")
            archived = manifest?.state in setOf("REGISTERED", "FINALIZED")
            if (manifest?.state == "REGISTERED") "SKIP" else "EXECUTE"
        }
        checked(stages, "COMPRESS") {
            val compressed = evidence["COMPRESS"]
            if (archived) {
                "SKIP"
            } else if (compressed == null) {
                val main = evidence["SPLIT"]?.let { c -> c.files.singleOrNull { it.rowId == c.referenceId } }
                if (main != null) check(!present(File(main.path + ".gz"))) { "COMPRESSED_OUTPUT_WITHOUT_CHECKPOINT" }
                "EXECUTE"
            } else {
                compressed.files.forEach(::verify)
                "SKIP"
            }
        }
        stages.add(
            ResumeStagePlan(
                "SUBMIT_ENCODER",
                "EXECUTE",
                "Set SUBMISSION_UNKNOWN before contacting encoder; never repeat ambiguous submission",
            ),
        )
        val order = listOf("OWNERSHIP", "INPUT", "INSPECT", "REGISTER", "SPLIT", "COMPRESS", "UPLOAD_ORIGINAL", "SUBMIT_ENCODER")
        return ResumePlan(id, "PROCESS", stages.sortedBy { order.indexOf(it.stage) })
    }

    private fun checked(
        stages: MutableList<ResumeStagePlan>,
        stage: String,
        body: () -> String,
    ) {
        try {
            stages.add(ResumeStagePlan(stage, body(), "Prerequisites verified; revalidate immediately before execution"))
        } catch (e: Exception) {
            if (ProcessingCancellation.isInterrupted(e)) throw e
            val reason =
                when (e) {
                    is NoSuchFileException -> "LOCAL_ARTIFACT_MISSING"
                    is java.io.IOException -> "ARTIFACT_INACCESSIBLE"
                    is IllegalStateException -> e.message?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "ARTIFACT_OR_METADATA_CHANGED"
                    else -> "PREREQUISITE_UNAVAILABLE_OR_CHANGED"
                }
            stages.add(
                ResumeStagePlan(
                    stage,
                    "BLOCKED",
                    reason,
                    listOf("Restore verified prerequisites or reconcile the retained owner; no automatic overwrite"),
                ),
            )
        }
    }
}
