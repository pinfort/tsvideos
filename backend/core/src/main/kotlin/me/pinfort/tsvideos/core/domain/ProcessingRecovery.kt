package me.pinfort.tsvideos.core.domain

data class LocalCheckpointFile(
    val path: String,
    val size: Long,
    val modified: Long,
    val sha256: String,
    val rowId: Long? = null,
)

data class CheckpointEvidence(
    val files: List<LocalCheckpointFile> = emptyList(),
    val referenceId: Long? = null,
    val drops: Int? = null,
    val duration: Double? = null,
)

data class ProcessingCheckpoint(
    val id: Long,
    val jobId: Long,
    val attemptId: Long,
    val stage: String,
    val evidence: String,
)

data class CallbackOutput(
    val file: LocalCheckpointFile,
    val target: String,
    val mime: String?,
    val encoding: String?,
)

data class CallbackInputManifest(
    val jobId: Long,
    val itemId: Int,
    val splitId: Long,
    val inPath: String,
    val outputs: List<CallbackOutput>,
    val cleanupFiles: List<LocalCheckpointFile> = emptyList(),
)

data class ResumeStagePlan(
    val stage: String,
    val action: String,
    val reason: String,
    val prerequisites: List<String> = emptyList(),
)

data class ResumePlan(
    val jobId: Long,
    val mode: String,
    val stages: List<ResumeStagePlan>,
) {
    val blocked: Boolean get() = stages.any { it.action == "BLOCKED" }
}

class ResumeBlocked(
    val reason: String,
) : IllegalStateException(reason)

class ResumeJobMissing : IllegalArgumentException("Processing job not found")
