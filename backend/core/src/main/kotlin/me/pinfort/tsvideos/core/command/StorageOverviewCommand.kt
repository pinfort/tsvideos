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
    // Nested commands carry no isolation/readOnly of their own, so they join this snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun overview(): StorageOverview {
        val totals = stats.summarize().totals
        val references = mapper.references()
        val recordings =
            references.groupBy { it.programId }.mapValues { (id, rows) ->
                StorageRecording(id, rows.first().programName, rows.distinctBy { it.fileId }.sumOf { registeredSize(it.size) })
            }
        // detect() reads programs inner-joined to executed_file, a subset of the LEFT JOIN rows in references(),
        // and both run in the same snapshot, so every grouped program has an entry.
        val groups = duplicates.detect()
        val candidates =
            groups.map { group ->
                StorageDuplicateCandidate(
                    group.reason,
                    recordings.getValue(group.recommended.id),
                    group.others.map { recordings.getValue(it.id) },
                )
            }
        // Duplicate groups are a partition (union-find), so no program is retained in one group and removable in another.
        val removalIds = candidates.flatMap { it.removable }.map { it.id }.toSet()
        val paths = references.filter { it.fileId != null && !it.file.isNullOrBlank() }.groupBy { normalizedPath(it.file!!) }
        var bytes = 0L
        var excluded = 0L
        paths.values.forEach { rows ->
            if (rows.any { it.programId in removalIds }) {
                if (rows.all { it.programId in removalIds }) {
                    bytes += rows.minOf { registeredSize(it.size) }
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

    internal companion object {
        // Missing or negative registered sizes count as zero everywhere.
        private fun registeredSize(size: Long?): Long = (size ?: 0).coerceAtLeast(0)

        // Conservative Windows/SMB alias normalization: ambiguous stores sharing a path are considered shared.
        // Lowercasing can merge distinct paths on a case-sensitive store, which only under-estimates reclaimable bytes.
        internal fun normalizedPath(path: String): String {
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
}
