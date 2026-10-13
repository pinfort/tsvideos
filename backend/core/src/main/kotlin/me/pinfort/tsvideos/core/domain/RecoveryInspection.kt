package me.pinfort.tsvideos.core.domain

data class InspectionArtifact(
    val path: String,
    val status: String,
    val verification: String,
    val reason: String,
)

data class InspectionRecord(
    val type: String,
    val id: Long,
    val path: String?,
    val status: String,
)

data class InspectionUpload(
    val id: Long,
    val state: String,
    val finalPath: String,
    val temporaryPath: String,
    val sourcePath: String,
    val owned: Boolean,
    val createdFileId: Long?,
    val verification: String = "UNCHECKED",
)

data class InspectionStage(
    val stage: String,
    val status: String,
    val action: String,
    val reason: String,
    val prerequisites: List<String>,
)

data class InspectionAttempt(
    val number: Int,
    val kind: String,
    val outcome: String,
    val activityAt: String,
    val errorCode: String?,
)

data class RecoveryInspection(
    val recording: String?,
    val candidateJobIds: List<Long>,
    val jobState: String?,
    val status: String,
    val records: List<InspectionRecord> = emptyList(),
    val localArtifacts: List<InspectionArtifact> = emptyList(),
    val nasArtifacts: List<InspectionArtifact> = emptyList(),
    val uploads: List<InspectionUpload> = emptyList(),
    val stages: List<InspectionStage> = emptyList(),
    val history: List<InspectionAttempt> = emptyList(),
    val resumePossible: Boolean = false,
    val resumeCommand: List<String>? = null,
    val prerequisites: List<String> = emptyList(),
    val manualOptions: List<String> = emptyList(),
    val checkpoints: Map<String, CheckpointEvidence> = emptyMap(),
    val callback: CallbackInputManifest? = null,
) {
    val exitCode: Int get() = if (status in setOf("BLOCKED", "INCONSISTENT", "AMBIGUOUS")) 4 else 0
}
