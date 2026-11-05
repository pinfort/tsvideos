package me.pinfort.tsvideos.library.domain

/**
 * One reviewed duplicate group and the members chosen for deletion. [programIds] must be exactly the members of a
 * group detected now with [reason], and [deleteIds] must leave at least one of them.
 */
data class DuplicateDeletionGroup(
    val reason: DuplicateGroup.Reason,
    val programIds: List<Long>,
    val deleteIds: List<Long>,
)

data class DuplicateDeletionRequest(
    val groups: List<DuplicateDeletionGroup>,
)

/** [DuplicateDeletionGroup] with sorted IDs and the members it keeps. */
data class DuplicateDeletionPlanGroup(
    val reason: DuplicateGroup.Reason,
    val programIds: List<Long>,
    val deleteIds: List<Long>,
    val keptIds: List<Long>,
)

/**
 * Preview of a duplicate-review deletion. [request] and [targets] have the shape of a bulk DELETE preview; [keptTargets]
 * are snapshots of the recordings that must still exist unchanged when each target is deleted.
 */
data class DuplicateDeletionPlan(
    val request: BulkRequest,
    val groups: List<DuplicateDeletionPlanGroup>,
    val targets: List<BulkTarget>,
    val keptTargets: List<BulkTarget>,
    val originalSourcePreserved: Boolean = true,
)

data class DuplicateDeletionApplyRequest(
    val groups: List<DuplicateDeletionGroup>,
    val fingerprints: Map<Long, String>,
    val keptFingerprints: Map<Long, String>,
)
