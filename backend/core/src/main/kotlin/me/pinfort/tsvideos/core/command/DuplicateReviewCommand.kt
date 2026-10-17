package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.DuplicateRecommendation
import me.pinfort.tsvideos.core.domain.DuplicateReview
import me.pinfort.tsvideos.core.domain.DuplicateReviewGroup
import me.pinfort.tsvideos.core.domain.DuplicateReviewMember
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Component
class DuplicateReviewCommand(
    private val duplicates: DuplicateProgramCommand,
    private val files: CreatedFileMapper,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun review(): DuplicateReview {
        val groups = duplicates.detect()
        // Batched instead of one query per member; chunked so a huge duplicate set stays within placeholder limits.
        val movedFiles =
            groups
                .flatMap { group -> group.programs.map { it.executedFileId } }
                .distinct()
                .chunked(ID_CHUNK_SIZE)
                .flatMap { ids -> files.selectMovedByExecutedFileIds(ids) }
                .groupBy({ it.executedFileId }, { it.toDomain() })
                .mapValues { (_, files) -> files.sortedBy { it.id } }
        return DuplicateReview(
            groups.map { group ->
                // DuplicateGroup guarantees 2+ members, sorted by keep order so the recommended one comes first.
                val winner = group.recommended
                val runnerUp = group.programs[1]
                DuplicateReviewGroup(
                    group.reason,
                    winner.id,
                    DuplicateRecommendation(
                        DuplicateProgramCommand.keepCriteria.map { it.first },
                        DuplicateProgramCommand.decisiveCriterion(winner, runnerUp),
                        runnerUp.id,
                        winner.drops.takeIf { it >= 0 },
                        winner.status,
                        winner.duration,
                        winner.size,
                        winner.id,
                    ),
                    group.programs.map { program ->
                        DuplicateReviewMember(program, movedFiles[program.executedFileId].orEmpty())
                    },
                )
            },
        )
    }

    companion object {
        private const val ID_CHUNK_SIZE = 1000
    }
}
