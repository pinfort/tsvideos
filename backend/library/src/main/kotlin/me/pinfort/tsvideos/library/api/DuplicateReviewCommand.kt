package me.pinfort.tsvideos.library.api

import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.library.domain.DuplicateRecommendation
import me.pinfort.tsvideos.library.domain.DuplicateReview
import me.pinfort.tsvideos.library.domain.DuplicateReviewGroup
import me.pinfort.tsvideos.library.domain.DuplicateReviewMember
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Component
class DuplicateReviewCommand(
    private val duplicates: DuplicateProgramCommand,
    private val files: CreatedFileCatalog,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun review(): DuplicateReview {
        val groups = duplicates.detect()
        // Batched instead of one query per member.
        val movedFiles = files.movedFilesByRecording(groups.flatMap { group -> group.programs.map { it.executedFileId } })
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
}
