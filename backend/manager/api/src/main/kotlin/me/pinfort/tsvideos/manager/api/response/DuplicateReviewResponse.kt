package me.pinfort.tsvideos.manager.api.response

import me.pinfort.tsvideos.library.domain.DuplicateGroup
import me.pinfort.tsvideos.library.domain.DuplicateReview
import me.pinfort.tsvideos.library.domain.DuplicateReviewGroup

data class DuplicateReviewResponse(
    val groups: List<DuplicateReviewGroup>,
) {
    companion object {
        fun of(review: DuplicateReview): DuplicateReviewResponse = DuplicateReviewResponse(groups = review.groups)
    }
}

/** The group a hide/restore request named, with its members sorted, and whether it is now hidden. */
data class DuplicateDismissalResponse(
    val reason: DuplicateGroup.Reason,
    val programIds: List<Long>,
    val hidden: Boolean,
)
