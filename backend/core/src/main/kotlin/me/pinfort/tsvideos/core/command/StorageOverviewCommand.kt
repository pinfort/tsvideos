package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.StorageDuplicateCandidate
import me.pinfort.tsvideos.core.domain.StorageOverview
import me.pinfort.tsvideos.core.domain.StorageRecording
import me.pinfort.tsvideos.core.external.database.mapper.StorageOverviewMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

@Component
class StorageOverviewCommand(
    private val stats: LibraryStatsCommand,
    private val duplicates: DuplicateProgramCommand,
    private val mapper: StorageOverviewMapper,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun overview(): StorageOverview {
        val totals = stats.summarize().totals
        val references = mapper.references()
        val recordings =
            references.groupBy { it.programId }.mapValues { (id, rows) ->
                StorageRecording(id, rows.first().programName, rows.distinctBy { it.fileId }.sumOf { it.size ?: 0 })
            }
        val groups = duplicates.detect()
        val candidates =
            groups.map { group ->
                StorageDuplicateCandidate(
                    group.reason,
                    recordings.getValue(group.recommended.id),
                    group.others.map { recordings.getValue(it.id) },
                )
            }
        val removalIds = candidates.flatMap { it.removable }.map { it.id }.toSet()
        val paths = references.filter { it.fileId != null && !it.file.isNullOrBlank() }.groupBy { normalizedPath(it.file!!) }
        var bytes = 0L
        var excluded = 0L
        paths.values.forEach { rows ->
            if (rows.any { it.programId in removalIds }) {
                if (rows.all { it.programId in removalIds }) {
                    bytes += rows.minOf { (it.size ?: 0).coerceAtLeast(0) }
                } else {
                    excluded++
                }
            }
        }
        return StorageOverview(
            totals,
            recordings.values.sortedWith(compareByDescending<StorageRecording> { it.registeredBytes }.thenBy { it.id }).take(20),
            candidates,
            bytes,
            excluded,
        )
    }

    // Conservative Windows/SMB alias normalization: ambiguous stores sharing a path are considered shared.
    private fun normalizedPath(path: String): String {
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.lastIndex) else parts.add(it)
                else -> parts.add(it.lowercase(Locale.ROOT))
            }
        }
        return parts.joinToString("/")
    }
}
