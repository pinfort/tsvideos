package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.ProgramSearchQuery

/** Only fixed SQL fragments are interpolated. All user values remain bound parameters. Ledger tables only. */
class ProgramSearchSql {
    fun search(query: ProgramSearchQuery): String {
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
            ${fromWhere(query)}
            ORDER BY $column $direction, pg.id $direction
            LIMIT #{limit} OFFSET #{offset}
            """.trimIndent()
    }

    fun count(query: ProgramSearchQuery): String = "SELECT COUNT(*) ${fromWhere(query)}"

    private fun fromWhere(query: ProgramSearchQuery): String {
        val conditions = mutableListOf<String>()
        // The schema is utf8mb4 (db/migrations). An explicit case-insensitive collation keeps matching independent of
        // the column default; it rules out index use, as the old LIKE '%x%' did, so name search is a full scan.
        if (query.name != null) conditions += "LOCATE(#{name} COLLATE utf8mb4_unicode_ci, pg.name COLLATE utf8mb4_unicode_ci) > 0"
        if (query.channel != null) conditions += "ex.channel = #{channel}"
        if (query.recordedFrom != null) conditions += "ex.recorded_at >= #{recordedFrom}"
        if (query.recordedTo != null) conditions += "ex.recorded_at < #{recordedTo}"
        if (query.status != null) conditions += "pg.status = #{status}"
        // Negative drops (-1) mean the drop check never ran; such recordings never satisfy a numeric limit.
        if (query.minDrops != null || query.maxDrops != null) conditions += "ex.drops >= 0"
        if (query.minDrops != null) conditions += "ex.drops >= #{minDrops}"
        if (query.maxDrops != null) conditions += "ex.drops <= #{maxDrops}"
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
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        return "FROM program pg LEFT OUTER JOIN executed_file ex ON pg.executed_file_id = ex.id$where"
    }
}
