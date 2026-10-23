package me.pinfort.tsvideos.library.domain

enum class CleanupMode { DUPLICATES, RETENTION }

data class CleanupCriteria(
    val mode: CleanupMode,
    val recordedBefore: String? = null,
    val channel: String? = null,
    val keepIds: List<Long> = emptyList(),
)

data class CleanupFile(
    val id: Long,
    val file: String,
    val size: Long,
    val status: String,
)

data class CleanupTarget(
    val id: Long,
    val fingerprint: String,
    val reason: String,
    val eligible: Boolean,
    val estimatedMetadataBytes: Long,
    val originalFile: String?,
    val splitFiles: List<String>,
    val createdFiles: List<CleanupFile>,
)

data class CleanupGroup(
    val memberIds: List<Long>,
    val keepId: Long,
)

data class CleanupPlan(
    val schemaVersion: Int = 1,
    val createdAt: String,
    val action: String = "DELETE",
    val criteria: CleanupCriteria,
    val groups: List<CleanupGroup>,
    val targets: List<CleanupTarget>,
    val keptTargets: List<CleanupTarget>,
)
