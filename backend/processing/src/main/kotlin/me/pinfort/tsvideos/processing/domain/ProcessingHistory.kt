package me.pinfort.tsvideos.processing.domain

enum class ProcessingAttemptKind { PROCESS, CALLBACK }

enum class ProcessingOutcome { ACTIVE, COMPLETED, FAILED, INTERRUPTED }

enum class ProcessingStage { INSPECT, QUALITY_CHECK, SPLIT, COMPRESS, UPLOAD_ORIGINAL, SUBMIT_ENCODER, UPLOAD_ENCODED, FINALIZE }

/** Only these static classifications may enter history; exception messages and stack traces are excluded. */
enum class ProcessingErrorCode {
    INVALID_INPUT,
    IO_FAILURE,
    DATABASE_FAILURE,
    INTERRUPTED,
    OPERATION_FAILED,
    ENCODER_FAILURE,
    QUALITY_QUARANTINED,
}

/** What a stage's artifact reference points at. */
enum class ProcessingArtifactType { INPUT, SPLITTED_FILE, UPLOAD_MANIFEST, PROGRAM }

data class ProcessingHistoryIdentity(
    val jobId: Long,
    val identityKey: String,
    val inputPath: String,
    val inputSize: Long,
    val modifiedMillis: Long,
    val lastAttempt: Int,
)

/** Timestamp strings are canonical ISO-8601 UTC from DATETIME fields written with UTC_TIMESTAMP(6). */
data class ProcessingAttempt(
    val id: Long,
    val jobId: Long,
    val attemptNumber: Int,
    val kind: ProcessingAttemptKind,
    val outcome: ProcessingOutcome,
    val startedAt: String,
    val endedAt: String?,
    val activityAt: String,
    val errorCode: ProcessingErrorCode?,
)

data class ProcessingStageEvent(
    val id: Long,
    val attemptId: Long,
    val stage: ProcessingStage,
    val outcome: ProcessingOutcome,
    val startedAt: String,
    val endedAt: String?,
    val errorCode: ProcessingErrorCode?,
    val artifactType: ProcessingArtifactType?,
    val artifactId: Long?,
    val artifactPath: String?,
)

data class ProcessingArtifact(
    val type: ProcessingArtifactType,
    val id: Long?,
    val path: String?,
)

data class ProcessingAttemptHistory(
    val identity: ProcessingHistoryIdentity,
    val attempt: ProcessingAttempt,
    val stages: List<ProcessingStageEvent>,
)

data class ProcessingStatus(
    val jobId: Long,
    val identityKey: String,
    val inputPath: String,
    /** A [ProcessingJob.State] name, or [RETIRED] once the job row is gone and only its history remains. */
    val jobState: String,
    val latestAttempt: ProcessingAttempt?,
    val activeAttempts: List<ProcessingAttempt>,
) {
    companion object {
        const val RETIRED = "RETIRED"
    }
}
