package me.pinfort.tsvideos.library.api

import me.pinfort.tsvideos.library.domain.BulkAction
import me.pinfort.tsvideos.library.domain.BulkApplyRequest
import me.pinfort.tsvideos.library.domain.BulkInterruptedException
import me.pinfort.tsvideos.library.domain.BulkOutcome
import me.pinfort.tsvideos.library.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.library.domain.BulkRequest
import me.pinfort.tsvideos.library.domain.BulkResult
import me.pinfort.tsvideos.library.domain.DuplicateDeletionApplyRequest
import me.pinfort.tsvideos.library.domain.DuplicateDeletionGroup
import me.pinfort.tsvideos.library.domain.DuplicateDeletionPlan
import me.pinfort.tsvideos.library.domain.DuplicateDeletionPlanGroup
import me.pinfort.tsvideos.library.domain.DuplicateDeletionRequest
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import me.pinfort.tsvideos.library.domain.InvalidBulkRequestException
import me.pinfort.tsvideos.library.domain.StaleDuplicateGroupException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Deletion from the duplicate review (#208). Unlike the generic bulk DELETE, every request names whole detected groups
 * and the server, not only the UI, keeps at least one member of each: an all-selected group is rejected, and each
 * deletion runs [BulkLibraryCommand.applyGuarded]'s guard under the whole-ledger lock, re-checking that the group is
 * still detected as previewed (minus this call's successful deletions) and that its kept members still exist unchanged.
 */
@Component
class DuplicateDeletionCommand(
    private val duplicates: DuplicateProgramCommand,
    private val bulk: BulkLibraryCommand,
    transactionManager: PlatformTransactionManager,
) {
    private val read =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    private val fingerprintPattern = Regex("[a-f0-9]{64}")

    /** @throws InvalidBulkRequestException for a malformed request @throws StaleDuplicateGroupException for a group not detected now */
    fun preview(input: DuplicateDeletionRequest): DuplicateDeletionPlan {
        val groups = validate(input.groups)
        bulk.fillPathKeys()
        return read.execute {
            val detected = detectedKeys()
            if (groups.any { key(it.reason, it.programIds) !in detected }) throw StaleDuplicateGroupException()
            val deleteIds = groups.flatMap { it.deleteIds }.sorted()
            val keptIds = groups.flatMap { it.keptIds }.sorted()
            val targets = bulk.previewInTransaction(BulkRequest(deleteIds, BulkAction.DELETE))
            DuplicateDeletionPlan(
                targets.request,
                groups,
                targets.targets,
                bulk.previewInTransaction(BulkRequest(keptIds, BulkAction.DELETE)).targets,
            )
        }
    }

    /**
     * Deletes the previewed targets one by one. A target is deleted only if, at that moment, its group is detected
     * with the previewed reason and exactly the previewed members minus this call's successful deletions, and that
     * group's kept members match their preview fingerprints; otherwise it is STALE and nothing of it is touched.
     * An interrupt stops before the next target; the remaining targets are reported FAILED.
     */
    fun apply(input: DuplicateDeletionApplyRequest): BulkResult {
        val groups = validate(input.groups)
        val keptIds = groups.flatMap { it.keptIds }.toSet()
        ensure(input.keptFingerprints.keys == keptIds) { "Every kept recording requires its preview fingerprint" }
        ensure(input.keptFingerprints.values.all { it.matches(fingerprintPattern) }) { "Invalid fingerprint" }
        val deleteIds = groups.flatMap { it.deleteIds }.sorted()
        val request = BulkApplyRequest(BulkRequest(deleteIds, BulkAction.DELETE), input.fingerprints)
        return try {
            bulk.applyGuarded(request) { id, completed ->
                guard(id, groups.single { id in it.deleteIds }, input.keptFingerprints, completed)
            }
        } catch (error: BulkInterruptedException) {
            val done = error.completed.map { it.id }.toSet()
            val remaining =
                deleteIds.filter { it !in done }.map { id ->
                    val message =
                        if (id == error.interruptedId) {
                            "Interrupted. NAS files may be partially deleted; inspect preview paths before retry. Original recording preserved."
                        } else {
                            "Interrupted before this target; nothing was changed."
                        }
                    BulkOutcome(id, BulkOutcomeStatus.FAILED, message)
                }
            BulkResult(error.completed + remaining)
        }
    }

    // Runs inside the target's transaction with the whole ledger locked (BulkLibraryCommand.applyGuarded).
    private fun guard(
        id: Long,
        group: DuplicateDeletionPlanGroup,
        keptFingerprints: Map<Long, String>,
        completed: Set<Long>,
    ): BulkOutcome? {
        val kept = bulk.previewInTransaction(BulkRequest(group.keptIds, BulkAction.DELETE)).targets
        if (kept.any { it.snapshot == null || it.fingerprint != keptFingerprints[it.id] }) {
            return BulkOutcome(
                id,
                BulkOutcomeStatus.STALE,
                "A recording to keep was deleted or changed; nothing was deleted. Preview again",
            )
        }
        val expected = group.programIds.filter { it !in completed }
        if (key(group.reason, expected) !in detectedKeys()) {
            return BulkOutcome(id, BulkOutcomeStatus.STALE, "Duplicate group changed; nothing was deleted. Preview again")
        }
        return null
    }

    private fun detectedKeys(): Set<String> = duplicates.detect().map { group -> key(group.reason, group.programs.map { it.id }) }.toSet()

    private fun validate(input: List<DuplicateDeletionGroup>): List<DuplicateDeletionPlanGroup> {
        ensure(input.isNotEmpty()) { "Select recordings to delete" }
        val groups =
            input.map { group ->
                val members = group.programIds
                val deletes = group.deleteIds
                ensure(members.size >= 2 && members.all { it > 0 } && members.distinct().size == members.size) {
                    "A duplicate group has at least two unique positive program IDs"
                }
                ensure(deletes.isNotEmpty() && deletes.distinct().size == deletes.size && members.containsAll(deletes)) {
                    "Selected recordings must be members of their duplicate group"
                }
                ensure(deletes.size < members.size) { "Keep at least one recording of each duplicate group" }
                DuplicateDeletionPlanGroup(group.reason, members.sorted(), deletes.sorted(), (members - deletes.toSet()).sorted())
            }
        val all = groups.flatMap { it.programIds }
        ensure(all.distinct().size == all.size) { "A recording can belong to only one duplicate group" }
        val deletes = groups.sumOf { it.deleteIds.size }
        val keeps = groups.sumOf { it.keptIds.size }
        ensure(deletes <= MAX_TARGETS && keeps <= MAX_TARGETS) { "Select at most $MAX_TARGETS recordings to delete and to keep" }
        return groups
    }

    private inline fun ensure(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw InvalidBulkRequestException(message())
    }

    private fun key(
        reason: DuplicateGroup.Reason,
        ids: List<Long>,
    ): String = "${reason.name}:${ids.sorted().joinToString(",")}"

    private companion object {
        /** BulkLibraryCommand's per-request limit. */
        const val MAX_TARGETS = 100
    }
}
