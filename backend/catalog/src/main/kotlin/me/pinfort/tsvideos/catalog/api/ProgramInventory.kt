package me.pinfort.tsvideos.catalog.api

import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.persistence.ProgramMapper
import org.springframework.stereotype.Component

/**
 * Library-wide reads of `program` rows (duplicate detection, cleanup, verification and health views). No transaction
 * of its own: reads join the caller's transaction, so a `REPEATABLE_READ` caller combines them with its own reads in
 * one snapshot. Results are held in memory, which suits the current library size (thousands of programs).
 */
@Component
class ProgramInventory internal constructor(
    private val programMapper: ProgramMapper,
) {
    /** Programs whose recording row exists (inner join), in no particular order. */
    fun recordedPrograms(): List<Program> = programMapper.selectAll().map { it.toDomain() }

    /** Every program by id, including programs whose recording row is gone (their recording fields are defaults). */
    fun allPrograms(): List<Program> = programMapper.selectAllOrderById().map { it.toDomain() }

    /** Every program ID, ascending. */
    fun allIds(): List<Long> = programMapper.selectAllIds()

    fun exists(id: Long): Boolean = programMapper.findId(id) != null

    /** Summaries of the given programs by id; IDs that no longer exist are simply absent. Empty input reads nothing. */
    fun summaries(ids: Collection<Long>): List<ProgramSummary> =
        ids
            .distinct()
            .sorted()
            .chunked(ID_CHUNK_SIZE)
            .flatMap { programMapper.summariesByIds(it) }

    /** Programs in [status], by id. */
    fun withStatus(status: Program.Status): List<ProgramSummary> = programMapper.summariesByStatus(status.name)

    /** Programs whose recording has a positive drop count, by id. */
    fun withDrops(): List<ProgramSummary> = programMapper.summariesWithDrops()

    private companion object {
        // Keeps a large ID set within the driver's placeholder limits.
        const val ID_CHUNK_SIZE = 1000
    }
}

/** A program's name, state and drop count; [drops] is null when its recording row is gone. */
data class ProgramSummary(
    val id: Long,
    val name: String,
    val status: Program.Status,
    val drops: Int?,
)
