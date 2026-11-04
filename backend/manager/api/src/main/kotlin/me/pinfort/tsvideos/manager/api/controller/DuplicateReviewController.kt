package me.pinfort.tsvideos.manager.api.controller

import me.pinfort.tsvideos.library.api.DuplicateReviewCommand
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import me.pinfort.tsvideos.library.domain.DuplicateGroupIdentity
import me.pinfort.tsvideos.library.domain.StaleDuplicateGroupException
import me.pinfort.tsvideos.manager.api.response.DuplicateDismissalResponse
import me.pinfort.tsvideos.manager.api.response.DuplicateReviewResponse
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

// Elements are nullable only so that `[1, null]` is a 400 instead of a null slipping into a List<Long>.
data class DuplicateGroupRequest(
    val reason: DuplicateGroup.Reason,
    val programIds: List<Long?>,
)

@RestController
class DuplicateReviewController(
    private val command: DuplicateReviewCommand,
) {
    @GetMapping("/api/v1/programs/duplicates")
    fun get(
        @RequestParam(defaultValue = "false") includeHidden: Boolean,
    ): DuplicateReviewResponse = DuplicateReviewResponse.of(command.review(includeHidden))

    @PostMapping("/api/v1/programs/duplicates/hide")
    fun hide(
        @RequestBody body: DuplicateGroupRequest,
    ): DuplicateDismissalResponse =
        respond(body, hidden = true) {
            command.hide(it)
        }

    @PostMapping("/api/v1/programs/duplicates/restore")
    fun restore(
        @RequestBody body: DuplicateGroupRequest,
    ): DuplicateDismissalResponse =
        respond(body, hidden = false) {
            command.restore(it)
        }

    private fun respond(
        body: DuplicateGroupRequest,
        hidden: Boolean,
        action: (DuplicateGroupIdentity) -> Unit,
    ): DuplicateDismissalResponse {
        val ids = body.programIds.map { it ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Program IDs must not be null") }
        val identity = DuplicateGroupIdentity(body.reason, ids)
        try {
            action(identity)
        } catch (error: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
        } catch (_: StaleDuplicateGroupException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The duplicate group is no longer detected; reload the review")
        } catch (_: PessimisticLockingFailureException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Concurrent change, retry the request")
        }
        return DuplicateDismissalResponse(identity.reason, ids.sorted(), hidden)
    }
}
