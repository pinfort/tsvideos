package me.pinfort.tsvideos.catalog.api

import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.catalog.persistence.BulkLibraryMapper
import me.pinfort.tsvideos.catalog.persistence.CreatedFileDto
import me.pinfort.tsvideos.catalog.persistence.CreatedFileMapper
import me.pinfort.tsvideos.catalog.persistence.ExecutedFileMapper
import me.pinfort.tsvideos.catalog.persistence.ProgramMapper
import me.pinfort.tsvideos.catalog.persistence.SplittedFileMapper
import org.springframework.stereotype.Component

/**
 * Reads a program together with every ledger row a coordinated change would touch, for previews and for
 * re-validation under [LedgerLocks]. No transaction of its own: reads join the caller's transaction, so a
 * `REPEATABLE_READ` caller sees one snapshot and a locking caller sees the rows it has locked.
 */
@Component
class LedgerSnapshots internal constructor(
    private val programs: ProgramMapper,
    private val executed: ExecutedFileMapper,
    private val splits: SplittedFileMapper,
    private val created: CreatedFileMapper,
    private val bulk: BulkLibraryMapper,
) {
    /** Every `created_file` row by id, including rows whose `splitted_file` is gone. */
    fun allCreatedFiles(): List<CreatedFileSnapshot> = bulk.allFiles().map { it.toSnapshot() }

    /**
     * Every `created_file` row registered at one of [paths] or at a normalized alias of one ([CreatedFilePathKey]), by
     * id: an indexed `path_key` lookup, plus the rows without a stored key (written before migration 076 or by SQL
     * outside catalog, see [CreatedFileCatalog.fillMissingPathKeys]), whose paths are compared here.
     */
    fun createdFilesAt(paths: Collection<String>): List<CreatedFileSnapshot> {
        val byKey = paths.associateBy(CreatedFilePathKey::of)
        if (byKey.isEmpty()) return emptyList()
        val keyed =
            byKey.values
                .map(CreatedFilePathKey::hash)
                .chunked(KEY_CHUNK_SIZE)
                .flatMap { bulk.filesWithPathKeys(it) }
        return (keyed + bulk.filesWithoutPathKey())
            .filter { CreatedFilePathKey.of(it.file) in byKey }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .map { it.toSnapshot() }
    }

    /** Every created file of [programIds], one entry per (program, file), so a shared recording counts twice. */
    fun createdFilesOfPrograms(programIds: List<Long>): List<CreatedFileSnapshot> =
        bulk.createdFilesOfPrograms(programIds).map { it.toSnapshot() }

    /** The program and its recording, split files and created files (each by id); null when the program is gone. */
    fun programSnapshot(programId: Long): ProgramSnapshot? {
        val program = programs.find(programId)?.toDomain() ?: return null
        return ProgramSnapshot(
            program,
            executed.find(program.executedFileId)?.toDomain(),
            splits.selectByExecutedFileId(program.executedFileId).sortedBy { it.id }.map { it.toDomain() },
            created.selectByExecutedFileId(program.executedFileId).sortedBy { it.id }.map { it.toSnapshot() },
        )
    }

    /** IDs of the programs that reference [executedFileId], ascending. */
    fun referencingPrograms(executedFileId: Long): List<Long> = bulk.references(executedFileId)

    private companion object {
        // Keeps a large selection within placeholder limits.
        const val KEY_CHUNK_SIZE = 500
    }
}

/** A program with the ledger rows its coordinated delete or move would change. */
data class ProgramSnapshot(
    val program: Program,
    val executed: ExecutedFile?,
    val splitFiles: List<SplittedFile>,
    val createdFiles: List<CreatedFileSnapshot>,
)

/**
 * A `created_file` row exactly as stored. Unlike [CreatedFile] it has no derived properties, so its JSON form is the
 * stored columns only (the bulk preview publishes it).
 */
data class CreatedFileSnapshot(
    val id: Long,
    val splittedFileId: Long,
    val file: String,
    val size: Long,
    val mime: String?,
    val encoding: String?,
    val status: CreatedFile.Status,
    val width: Int?,
    val height: Int?,
)

internal fun CreatedFileDto.toSnapshot(): CreatedFileSnapshot {
    val domain = toDomain()
    return CreatedFileSnapshot(id, splittedFileId, file, size, mime, encoding, domain.status, width, height)
}
