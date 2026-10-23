package me.pinfort.tsvideos.library.domain

import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.Program

enum class DuplicateKeepCriterion { DROPS, STATUS, DURATION, SIZE, ID }

data class DuplicateRecommendation(
    val orderedCriteria: List<DuplicateKeepCriterion>,
    val decisiveCriterion: DuplicateKeepCriterion,
    val comparedWithProgramId: Long,
    val drops: Int?,
    val status: Program.Status,
    val durationSeconds: Double,
    val recordedBytes: Long,
    val id: Long,
)

data class DuplicateReviewMember(
    val program: Program,
    val availableFiles: List<CreatedFile>,
)

data class DuplicateReviewGroup(
    val reason: DuplicateGroup.Reason,
    val recommendedId: Long,
    val recommendation: DuplicateRecommendation,
    val members: List<DuplicateReviewMember>,
)

data class DuplicateReview(
    val groups: List<DuplicateReviewGroup>,
)
