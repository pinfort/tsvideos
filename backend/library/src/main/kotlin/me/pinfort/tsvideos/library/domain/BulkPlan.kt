package me.pinfort.tsvideos.library.domain

import me.pinfort.tsvideos.catalog.api.CreatedFileSnapshot
import me.pinfort.tsvideos.catalog.api.ProgramSnapshot
import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.SplittedFile

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

/**
 * Published as-is by `POST /api/v1/programs/bulk/preview`: the property names, values and enum names must stay those
 * of the stored rows. Built from catalog's [ProgramSnapshot].
 */
data class BulkSnapshot(
    val program: Program,
    val executed: ExecutedFile?,
    val splitFiles: List<SplittedFile>,
    val createdFiles: List<CreatedFileSnapshot>,
) {
    constructor(snapshot: ProgramSnapshot) : this(snapshot.program, snapshot.executed, snapshot.splitFiles, snapshot.createdFiles)
}

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
