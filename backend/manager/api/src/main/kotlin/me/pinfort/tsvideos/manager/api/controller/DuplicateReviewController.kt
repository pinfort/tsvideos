package me.pinfort.tsvideos.manager.api.controller

import me.pinfort.tsvideos.core.command.DuplicateReviewCommand
import me.pinfort.tsvideos.manager.api.response.DuplicateReviewResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class DuplicateReviewController(
    private val command: DuplicateReviewCommand,
) {
    @GetMapping("/api/v1/programs/duplicates")
    fun get(): DuplicateReviewResponse = DuplicateReviewResponse.of(command.review())
}
