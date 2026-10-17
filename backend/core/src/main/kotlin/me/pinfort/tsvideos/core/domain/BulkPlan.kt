package me.pinfort.tsvideos.core.domain

import me.pinfort.tsvideos.core.external.database.dto.CreatedFileDto
import me.pinfort.tsvideos.core.external.database.dto.ExecutedFileDto
import me.pinfort.tsvideos.core.external.database.dto.SplittedFileDto

enum class BulkAction { DELETE, MOVE }

/** A rejected bulk request (client error). Other exceptions, even IllegalArgumentException, are internal failures. */
class InvalidBulkRequestException(
    message: String,
) : IllegalArgumentException(message)

/**
 * A guarded bulk apply stopped on interrupt. [completed] holds the outcomes of targets finished before
 * [interruptedId], whose own changes may be partial (NAS files can be gone even though its DB rows rolled back).
 */
class BulkInterruptedException(
    val completed: List<BulkOutcome>,
    val interruptedId: Long?,
) : InterruptedException("Bulk operation interrupted")

enum class BulkOutcomeStatus { SUCCESS, FAILED, STALE, NOT_FOUND }

data class BulkRequest(
    val ids: List<Long>,
    val action: BulkAction,
    val directory: String? = null,
)

data class BulkApplyRequest(
    val request: BulkRequest,
    val fingerprints: Map<Long, String>,
)

data class BulkSnapshot(
    val program: Program,
    val executed: ExecutedFileDto?,
    val splitFiles: List<SplittedFileDto>,
    val createdFiles: List<CreatedFileDto>,
)

data class BulkTarget(
    val id: Long,
    val snapshot: BulkSnapshot?,
    val fingerprint: String,
    val destinations: Map<Long, String>,
    val eligible: Boolean,
    val reason: String?,
    val estimatedMetadataBytes: Long,
)

data class BulkPlan(
    val request: BulkRequest,
    val targets: List<BulkTarget>,
    val originalSourcePreserved: Boolean = true,
)

data class BulkOutcome(
    val id: Long,
    val status: BulkOutcomeStatus,
    val message: String,
)

data class BulkResult(
    val outcomes: List<BulkOutcome>,
)
