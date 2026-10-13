package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.DuplicateKeepCriterion
import me.pinfort.tsvideos.core.domain.DuplicateRecommendation
import me.pinfort.tsvideos.core.domain.DuplicateReview
import me.pinfort.tsvideos.core.domain.DuplicateReviewGroup
import me.pinfort.tsvideos.core.domain.DuplicateReviewMember
import me.pinfort.tsvideos.core.domain.Program
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
    fun review(): DuplicateReview =
        DuplicateReview(
            duplicates.detect().map { group ->
                val winner = group.recommended
                val runnerUp = group.programs[1]
                val decisive =
                    when {
                        dropRank(winner) != dropRank(runnerUp) -> DuplicateKeepCriterion.DROPS
                        winner.status != runnerUp.status -> DuplicateKeepCriterion.STATUS
                        winner.duration != runnerUp.duration -> DuplicateKeepCriterion.DURATION
                        winner.size != runnerUp.size -> DuplicateKeepCriterion.SIZE
                        else -> DuplicateKeepCriterion.ID
                    }
                DuplicateReviewGroup(
                    group.reason,
                    winner.id,
                    DuplicateRecommendation(
                        DuplicateKeepCriterion.entries,
                        decisive,
                        runnerUp.id,
                        winner.drops.takeIf {
                            it >= 0
                        },
                        winner.status,
                        winner.duration,
                        winner.size,
                        winner.id,
                    ),
                    group.programs.map { program ->
                        DuplicateReviewMember(
                            program,
                            files
                                .selectByExecutedFileId(program.executedFileId)
                                .map { it.toDomain() }
                                .filter {
                                    it.status ==
                                        CreatedFile.Status.FILE_MOVED
                                }.sortedBy { it.id },
                        )
                    },
                )
            },
        )

    private fun dropRank(program: Program): Int = if (program.drops < 0) Int.MAX_VALUE else program.drops
}
