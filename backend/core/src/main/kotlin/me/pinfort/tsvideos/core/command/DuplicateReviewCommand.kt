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
        // One query for every member's FILE_MOVED files instead of one per member.
        val movedFiles =
            groups
                .flatMap { group -> group.programs.map { it.executedFileId } }
                .toSet()
                .takeIf { it.isNotEmpty() }
                ?.let { ids -> files.selectMovedByExecutedFileIds(ids) }
                .orEmpty()
                .groupBy({ it.executedFileId }, { it.toCreatedFileDto().toDomain() })
        return DuplicateReview(
            groups.map { group ->
                // detect() only returns groups of 2+ members, sorted so the recommended one comes first.
                require(group.programs.size >= 2) { "duplicate group must have at least two programs" }
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
}
