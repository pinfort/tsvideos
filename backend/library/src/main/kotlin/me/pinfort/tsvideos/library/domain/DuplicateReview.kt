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

/**
 * @param hidden an operator marked this exact group (same reason and members) as an intentional duplicate. It only
 * affects the review; detection, the storage overview and cleanup plans are unchanged
 */
data class DuplicateReviewGroup(
    val reason: DuplicateGroup.Reason,
    val recommendedId: Long,
    val recommendation: DuplicateRecommendation,
    val members: List<DuplicateReviewMember>,
    val hidden: Boolean = false,
)

data class DuplicateReview(
    val groups: List<DuplicateReviewGroup>,
)

/**
 * Names one detected duplicate group by its reason and exact members, in any order. Hiding or restoring it requires
 * the detector to currently produce exactly this group.
 */
data class DuplicateGroupIdentity(
    val reason: DuplicateGroup.Reason,
    val programIds: List<Long>,
)

/** The named group is not currently detected: its members or reason changed, or it was never a group. */
class StaleDuplicateGroupException : RuntimeException("The duplicate group is no longer detected; reload the review")
