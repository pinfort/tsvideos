package me.pinfort.tsvideos.core.domain

/** Immutable retained evidence; sourcePath is also the in-place quarantine location. */
data class RecordingQualityReport(
    val attemptId: Long,
    val jobId: Long,
    val checkedAt: String,
    val action: QualityAction,
    val sourcePath: String,
    val identityKey: String,
    val findingsJson: String,
)
