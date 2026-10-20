package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.LibraryProgramFilter
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramSearchQuery
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.SelectProvider
import java.time.LocalDateTime

/**
 * Library-owned, read-only reference query: the program search narrowed by manual collection/tag membership.
 * It reads ledger tables (backend/docs/module-boundaries.md §5.2) and takes no lock.
 */
@Mapper
interface LibraryProgramSearchMapper {
    @SelectProvider(type = LibraryProgramSearchSql::class, method = "search")
    fun search(
        @Param("query") query: ProgramSearchQuery,
        @Param("filter") filter: LibraryProgramFilter,
    ): List<LibraryProgramRow>

    @SelectProvider(type = LibraryProgramSearchSql::class, method = "count")
    fun count(
        @Param("query") query: ProgramSearchQuery,
        @Param("filter") filter: LibraryProgramFilter,
    ): Long
}

/** One search result row; converted to a [Program] the same way catalog's search converts its rows. */
data class LibraryProgramRow(
    val id: Long,
    val name: String,
    val executedFileId: Long,
    val status: Program.Status,
    val drops: Int?,
    val size: Long?,
    val recordedAt: LocalDateTime?,
    val channel: String?,
    val title: String?,
    val channelName: String?,
    val duration: Double?,
) {
    fun toDomain(): Program =
        Program(
            id = id,
            name = name,
            executedFileId = executedFileId,
            status = status,
            drops = drops ?: -1,
            size = size ?: 0,
            recordedAt = recordedAt ?: LocalDateTime.MIN,
            channel = channel ?: "",
            title = title ?: "",
            channelName = channelName ?: "",
            duration = duration ?: -1.0,
        )
}

/**
 * Only fixed SQL fragments are interpolated. All user values remain bound parameters.
 * The ledger conditions, order and paging repeat catalog's program search exactly (LibraryProgramSearchCommandTest
 * compares the two); keep them in step when the catalog search changes.
 */
class LibraryProgramSearchSql {
    fun search(parameters: Map<String, Any>): String {
        val query = query(parameters)
        val column =
            when (query.sort) {
                ProgramSearchQuery.Sort.RECORDED_AT -> "ex.recorded_at"
                ProgramSearchQuery.Sort.DURATION -> "ex.duration"
                ProgramSearchQuery.Sort.SIZE -> "ex.size"
            }
        val direction =
            when (query.direction) {
                ProgramSearchQuery.Direction.ASC -> "ASC"
                ProgramSearchQuery.Direction.DESC -> "DESC"
            }
        return """
            SELECT pg.id, pg.name, pg.executed_file_id, pg.status, ex.drops, ex.size,
                ex.recorded_at, ex.channel, ex.title, ex.channelName, ex.duration
            ${fromWhere(parameters)}
            ORDER BY $column $direction, pg.id $direction
            LIMIT #{query.limit} OFFSET #{query.offset}
            """.trimIndent()
    }

    fun count(parameters: Map<String, Any>): String = "SELECT COUNT(*) ${fromWhere(parameters)}"

    private fun query(parameters: Map<String, Any>) = parameters["query"] as ProgramSearchQuery

    private fun fromWhere(parameters: Map<String, Any>): String {
        val query = query(parameters)
        val filter = parameters["filter"] as LibraryProgramFilter
        val conditions = mutableListOf<String>()
        if (query.name != null) {
            conditions += "LOCATE(#{query.name} COLLATE utf8mb4_unicode_ci, pg.name COLLATE utf8mb4_unicode_ci) > 0"
        }
        if (query.channel != null) conditions += "ex.channel = #{query.channel}"
        if (query.recordedFrom != null) conditions += "ex.recorded_at >= #{query.recordedFrom}"
        if (query.recordedTo != null) conditions += "ex.recorded_at < #{query.recordedTo}"
        if (query.status != null) conditions += "pg.status = #{query.status}"
        // Negative drops (-1) mean the drop check never ran; such recordings never satisfy a numeric limit.
        if (query.minDrops != null || query.maxDrops != null) conditions += "ex.drops >= 0"
        if (query.minDrops != null) conditions += "ex.drops >= #{query.minDrops}"
        if (query.maxDrops != null) conditions += "ex.drops <= #{query.maxDrops}"
        if (query.fileType != null) {
            val mime =
                when (query.fileType) {
                    ProgramSearchQuery.FileType.TS -> "cf.mime = '${CreatedFile.TS_MIME}'"
                    ProgramSearchQuery.FileType.ENCODED -> "cf.mime LIKE 'video/%' AND cf.mime <> '${CreatedFile.TS_MIME}'"
                }
            conditions +=
                """
                EXISTS (SELECT 1 FROM splitted_file sf JOIN created_file cf ON cf.splitted_file_id = sf.id
                    WHERE sf.executed_file_id = ex.id AND cf.status = 'FILE_MOVED' AND $mime)
                """.trimIndent()
        }
        if (filter.collectionId != null) {
            conditions +=
                "EXISTS (SELECT 1 FROM library_collection_program lcp WHERE lcp.program_id = pg.id AND lcp.label_id = #{filter.collectionId})"
        }
        if (filter.libraryTagId != null) {
            conditions +=
                "EXISTS (SELECT 1 FROM library_tag_program ltp WHERE ltp.program_id = pg.id AND ltp.label_id = #{filter.libraryTagId})"
        }
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        return "FROM program pg LEFT OUTER JOIN executed_file ex ON pg.executed_file_id = ex.id$where"
    }
}
