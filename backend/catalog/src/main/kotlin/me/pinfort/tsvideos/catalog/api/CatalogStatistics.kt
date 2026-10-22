package me.pinfort.tsvideos.catalog.api

import me.pinfort.tsvideos.catalog.persistence.LibraryStatsMapper
import me.pinfort.tsvideos.catalog.persistence.StorageOverviewMapper
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Per-row ledger facts that library statistics and the storage overview aggregate. No transaction of its own: reads
 * join the caller's transaction, so several reads taken in one `REPEATABLE_READ` transaction share a snapshot.
 */
@Component
class CatalogStatistics internal constructor(
    private val stats: LibraryStatsMapper,
    private val storage: StorageOverviewMapper,
) {
    /**
     * One row per program by id. A recording-time bound excludes programs whose recording row is gone; with no bound
     * they are kept with null recording fields.
     */
    fun programs(
        recordedFrom: LocalDateTime? = null,
        recordedTo: LocalDateTime? = null,
    ): List<ProgramStatsRow> =
        stats.programs(recordedFrom, recordedTo).map {
            ProgramStatsRow(it.id, it.executedFileId, it.status, it.drops, it.channel, it.recordedAt)
        }

    /** One row per created file (by id) of a recording referenced by a program within the bounds. */
    fun files(
        recordedFrom: LocalDateTime? = null,
        recordedTo: LocalDateTime? = null,
    ): List<FileStatsRow> =
        stats.files(recordedFrom, recordedTo).map { FileStatsRow(it.id, it.executedFileId, it.size, it.mime, it.encoding, it.status) }

    /** Every program with each created file of its recording (or one row without a file), by program then file id. */
    fun storageReferences(): List<StorageReference> =
        storage.references().map { StorageReference(it.programId, it.programName, it.fileId, it.file, it.size) }
}

/** [status] is the stored program status name; [drops] is null when the recording row is gone. */
data class ProgramStatsRow(
    val id: Long,
    val executedFileId: Long,
    val status: String,
    val drops: Int?,
    val channel: String?,
    val recordedAt: LocalDateTime?,
)

/** [status] is the stored created-file status name. */
data class FileStatsRow(
    val id: Long,
    val executedFileId: Long,
    val size: Long,
    val mime: String?,
    val encoding: String?,
    val status: String,
)

/** A program and one created file of its recording; the file fields are null when the recording has none. */
data class StorageReference(
    val programId: Long,
    val programName: String,
    val fileId: Long?,
    val file: String?,
    val size: Long?,
)
