package me.pinfort.tsvideos.core.domain

import java.time.LocalDateTime

/** Shared CLI/API search semantics; recording timestamps use the recording environment's local time. */
data class ProgramSearchQuery(
    val name: String? = null,
    val channel: String? = null,
    val recordedFrom: LocalDateTime? = null,
    val recordedTo: LocalDateTime? = null,
    val status: Program.Status? = null,
    val minDrops: Int? = null,
    val maxDrops: Int? = null,
    val fileType: FileType? = null,
    val sort: Sort = Sort.RECORDED_AT,
    val direction: Direction = Direction.DESC,
    val limit: Int = 100,
    val offset: Int = 0,
    val collectionId: Long? = null,
    val tagId: Long? = null,
) {
    enum class FileType { TS, ENCODED }

    enum class Sort { RECORDED_AT, DURATION, SIZE }

    enum class Direction { ASC, DESC }

    init {
        require(collectionId == null || collectionId > 0) { "collectionId must be positive" }
        require(tagId == null || tagId > 0) { "tagId must be positive" }
        require(
            recordedFrom == null || recordedTo == null || recordedFrom < recordedTo,
        ) { "recorded-from must be earlier than recorded-to" }
        require(minDrops == null || minDrops >= 0) { "min-drops must be nonnegative" }
        require(maxDrops == null || maxDrops >= 0) { "max-drops must be nonnegative" }
        require(minDrops == null || maxDrops == null || minDrops <= maxDrops) { "min-drops must not exceed max-drops" }
        require(limit in 1..500) { "limit must be between 1 and 500" }
        require(offset >= 0) { "offset must be nonnegative" }
    }
}

data class ProgramSearchPage(
    val items: List<Program>,
    val total: Long,
    val limit: Int,
    val offset: Int,
)
