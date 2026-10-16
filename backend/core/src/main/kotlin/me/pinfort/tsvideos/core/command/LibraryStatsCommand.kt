package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.LibraryStats
import me.pinfort.tsvideos.core.domain.LibraryStatsGroup
import me.pinfort.tsvideos.core.domain.LibraryStatsQuery
import me.pinfort.tsvideos.core.domain.LibraryStatsTotals
import me.pinfort.tsvideos.core.domain.Program
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
    // Rows are aggregated in Kotlin rather than SQL GROUP BY: each row is small at personal-library scale,
    // and the dedup/classification rules stay in one readable, testable place.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun summarize(query: LibraryStatsQuery = LibraryStatsQuery()): LibraryStats {
        // Both queries return one row per primary key (EXISTS does not multiply rows), so no deduplication is needed.
        val programs = mapper.programs(query)
        val files = mapper.files(query)
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
                    // Every group key is a property of the executed file, so each file falls into exactly one group.
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
        val (original, rest) = files.partition { it.mime.equals(TS_MIME, ignoreCase = true) && it.encoding.equals(GZIP, ignoreCase = true) }
        val (encoded, other) =
            rest.partition {
                it.mime?.startsWith("video/", ignoreCase = true) == true &&
                    !it.mime.equals(TS_MIME, ignoreCase = true)
            }
        val (moved, pending) = files.partition { it.status == CreatedFile.Status.FILE_MOVED.name }
        return LibraryStatsTotals(
            programs.size.toLong(),
            programs.count { it.status == Program.Status.ERROR.name }.toLong(),
            programs.sumOf { it.drops?.coerceAtLeast(0)?.toLong() ?: 0L },
            programs.count { it.drops == null || it.drops < 0 }.toLong(),
            files.size.toLong(),
            files.sumOf { it.size },
            original.sumOf { it.size },
            encoded.sumOf { it.size },
            other.sumOf { it.size },
            moved.sumOf { it.size },
            pending.sumOf { it.size },
        )
    }

    private companion object {
        const val TS_MIME = CreatedFile.TS_MIME
        const val GZIP = "gzip"
    }
}
