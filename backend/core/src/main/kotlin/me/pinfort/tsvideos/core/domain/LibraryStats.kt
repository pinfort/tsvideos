package me.pinfort.tsvideos.core.domain

import java.time.LocalDateTime

data class LibraryStatsQuery(
    val groupBy: Group? = null,
    val recordedFrom: LocalDateTime? = null,
    val recordedTo: LocalDateTime? = null,
) {
    enum class Group { CHANNEL, MONTH }

    init {
        require(recordedFrom == null || recordedTo == null || recordedFrom < recordedTo) {
            "recordedFrom must precede recordedTo"
        }
    }
}

data class LibraryStatsTotals(
    val programCount: Long,
    val failedProgramCount: Long,
    val droppedFrameCount: Long,
    val unknownDropsProgramCount: Long,
    val registeredFileCount: Long,
    val registeredBytes: Long,
    val originalTsBytes: Long,
    val encodedBytes: Long,
    val otherBytes: Long,
    val movedFileBytes: Long,
    val pendingFileBytes: Long,
    val verifiedPhysicalBytes: Long? = null,
)

data class LibraryStatsGroup(
    val key: String?,
    val totals: LibraryStatsTotals,
)

data class LibraryStats(
    val totals: LibraryStatsTotals,
    val groups: List<LibraryStatsGroup>,
)
