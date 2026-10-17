package me.pinfort.tsvideos.manager.api.response

import me.pinfort.tsvideos.core.domain.DuplicateReview
import me.pinfort.tsvideos.core.domain.DuplicateReviewGroup

data class DuplicateReviewResponse(
    val groups: List<DuplicateReviewGroup>,
) {
    companion object {
        fun of(review: DuplicateReview): DuplicateReviewResponse = DuplicateReviewResponse(groups = review.groups)
    }
}
