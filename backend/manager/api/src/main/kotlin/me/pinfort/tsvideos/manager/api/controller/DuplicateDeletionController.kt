package me.pinfort.tsvideos.manager.api.controller

import me.pinfort.tsvideos.library.api.DuplicateDeletionCommand
import me.pinfort.tsvideos.library.domain.BulkResult
import me.pinfort.tsvideos.library.domain.DuplicateDeletionApplyRequest
import me.pinfort.tsvideos.library.domain.DuplicateDeletionGroup
import me.pinfort.tsvideos.library.domain.DuplicateDeletionPlan
import me.pinfort.tsvideos.library.domain.DuplicateDeletionRequest
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import me.pinfort.tsvideos.library.domain.InvalidBulkRequestException
import me.pinfort.tsvideos.library.domain.StaleDuplicateGroupException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

// Elements are nullable only so that `[1, null]` is a 400 instead of a null slipping into a List<Long>.
data class DuplicateDeletionGroupRequest(
    val reason: DuplicateGroup.Reason,
    val programIds: List<Long?>,
    val deleteIds: List<Long?>,
)

data class DuplicateDeletionPreviewRequest(
    val groups: List<DuplicateDeletionGroupRequest?>,
)

data class DuplicateDeletionApplyBody(
    val groups: List<DuplicateDeletionGroupRequest?>,
    val fingerprints: Map<Long, String?>,
    val keptFingerprints: Map<Long, String?>,
)

/** Deletion from the duplicate review; the library command keeps at least one member of every group. */
@RestController
class DuplicateDeletionController(
    private val command: DuplicateDeletionCommand,
) {
    @PostMapping("/api/v1/programs/duplicates/delete/preview")
    fun preview(
        @RequestBody body: DuplicateDeletionPreviewRequest,
    ): DuplicateDeletionPlan = validated { command.preview(DuplicateDeletionRequest(groups(body.groups))) }

    @PostMapping("/api/v1/programs/duplicates/delete/apply")
    fun apply(
        @RequestBody body: DuplicateDeletionApplyBody,
    ): BulkResult =
        validated {
            command.apply(
                DuplicateDeletionApplyRequest(groups(body.groups), nonNull(body.fingerprints), nonNull(body.keptFingerprints)),
            )
        }

    private fun groups(groups: List<DuplicateDeletionGroupRequest?>): List<DuplicateDeletionGroup> =
        groups.map { group ->
            val value = group ?: throw InvalidBulkRequestException("Groups must not be null")
            DuplicateDeletionGroup(value.reason, nonNull(value.programIds), nonNull(value.deleteIds))
        }

    private fun nonNull(ids: List<Long?>): List<Long> = ids.map { it ?: throw InvalidBulkRequestException("Program IDs must not be null") }

    private fun nonNull(fingerprints: Map<Long, String?>): Map<Long, String> =
        fingerprints.mapValues { it.value ?: throw InvalidBulkRequestException("Fingerprints must not be null") }

    private fun <T> validated(action: () -> T): T =
        try {
            action()
        } catch (error: InvalidBulkRequestException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
        } catch (_: StaleDuplicateGroupException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The duplicate group is no longer detected; reload the review")
        }
}
