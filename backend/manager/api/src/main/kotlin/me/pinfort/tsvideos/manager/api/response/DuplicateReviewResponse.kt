package me.pinfort.tsvideos.manager.api.response

import me.pinfort.tsvideos.library.domain.DuplicateReview
import me.pinfort.tsvideos.library.domain.DuplicateReviewGroup

data class DuplicateReviewResponse(
    val groups: List<DuplicateReviewGroup>,
) {
    companion object {
        fun of(review: DuplicateReview): DuplicateReviewResponse = DuplicateReviewResponse(groups = review.groups)
    }
}
