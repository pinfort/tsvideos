package me.pinfort.tsvideos.manager.api.response

import me.pinfort.tsvideos.library.domain.LibraryStatsTotals
import me.pinfort.tsvideos.library.domain.StorageDuplicateCandidate
import me.pinfort.tsvideos.library.domain.StorageOverview
import me.pinfort.tsvideos.library.domain.StorageRecording

data class StorageOverviewResponse(
    val totals: LibraryStatsTotals,
    val largestRecordings: List<StorageRecording>,
    val duplicateCandidates: List<StorageDuplicateCandidate>,
    val estimatedReclaimableBytes: Long,
    val excludedSharedPathCount: Long,
    val verifiedReclaimableBytes: Long?,
) {
    companion object {
        fun of(overview: StorageOverview): StorageOverviewResponse =
            StorageOverviewResponse(
                totals = overview.totals,
                largestRecordings = overview.largestRecordings,
                duplicateCandidates = overview.duplicateCandidates,
                estimatedReclaimableBytes = overview.estimatedReclaimableBytes,
                excludedSharedPathCount = overview.excludedSharedPathCount,
                verifiedReclaimableBytes = overview.verifiedReclaimableBytes,
            )
    }
}
