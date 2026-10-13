package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.LibraryStats
import me.pinfort.tsvideos.core.domain.LibraryStatsGroup
import me.pinfort.tsvideos.core.domain.LibraryStatsQuery
import me.pinfort.tsvideos.core.domain.LibraryStatsTotals
import me.pinfort.tsvideos.core.external.database.dto.StatsFileDto
import me.pinfort.tsvideos.core.external.database.dto.StatsProgramDto
import me.pinfort.tsvideos.core.external.database.mapper.LibraryStatsMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

@Component
class LibraryStatsCommand(
    private val mapper: LibraryStatsMapper,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun summarize(query: LibraryStatsQuery = LibraryStatsQuery()): LibraryStats {
        val programs = mapper.programs(query).distinctBy { it.id }
        val files = mapper.files(query).distinctBy { it.id }
        val filesByExecuted = files.groupBy { it.executedFileId }
        val groups =
            if (query.groupBy == null) {
                emptyList()
            } else {
                programs
                    .groupBy {
                        when (query.groupBy) {
                            LibraryStatsQuery.Group.CHANNEL -> it.channel
                            LibraryStatsQuery.Group.MONTH -> it.recordedAt?.let { date -> YearMonth.from(date).toString() }
                            null -> null
                        }
                    }.entries
                    .sortedWith(compareBy(nullsFirst()) { it.key })
                    .map { (key, members) ->
                        val memberFiles = members.map { it.executedFileId }.distinct().flatMap { filesByExecuted[it].orEmpty() }
                        LibraryStatsGroup(key, totals(members, memberFiles))
                    }
            }
        return LibraryStats(totals(programs, files), groups)
    }

    private fun totals(
        programs: List<StatsProgramDto>,
        files: List<StatsFileDto>,
    ): LibraryStatsTotals {
        val original = files.filter { it.mime == TS_MIME && it.encoding == "gzip" }.sumOf { it.size }
        val encoded = files.filter { it.mime?.startsWith("video/") == true && it.mime != TS_MIME }.sumOf { it.size }
        val bytes = files.sumOf { it.size }
        val moved = files.filter { it.status == "FILE_MOVED" }.sumOf { it.size }
        return LibraryStatsTotals(
            programs.size.toLong(),
            programs.count { it.status == "ERROR" }.toLong(),
            programs.sumOf { (it.drops ?: -1).coerceAtLeast(0).toLong() },
            programs.count { it.drops == null || it.drops < 0 }.toLong(),
            files.size.toLong(),
            bytes,
            original,
            encoded,
            bytes - original - encoded,
            moved,
            bytes - moved,
        )
    }

    private companion object {
        const val TS_MIME = "video/vnd.dlna.mpeg-tts"
    }
}
