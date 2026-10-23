package me.pinfort.tsvideos.library.domain

data class StorageRecording(
    val id: Long,
    val name: String,
    val registeredBytes: Long,
)

data class StorageDuplicateCandidate(
    val reason: DuplicateGroup.Reason,
    val retained: StorageRecording,
    val removable: List<StorageRecording>,
)

data class StorageOverview(
    val totals: LibraryStatsTotals,
    val largestRecordings: List<StorageRecording>,
    val duplicateCandidates: List<StorageDuplicateCandidate>,
    val estimatedReclaimableBytes: Long,
    val excludedSharedPathCount: Long,
    val verifiedReclaimableBytes: Long? = null,
)
