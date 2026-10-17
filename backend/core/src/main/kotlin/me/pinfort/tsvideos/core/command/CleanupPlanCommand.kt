package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.BulkAction
import me.pinfort.tsvideos.core.domain.BulkApplyRequest
import me.pinfort.tsvideos.core.domain.BulkOutcome
import me.pinfort.tsvideos.core.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.core.domain.BulkRequest
import me.pinfort.tsvideos.core.domain.BulkResult
import me.pinfort.tsvideos.core.domain.CleanupCriteria
import me.pinfort.tsvideos.core.domain.CleanupFile
import me.pinfort.tsvideos.core.domain.CleanupGroup
import me.pinfort.tsvideos.core.domain.CleanupMode
import me.pinfort.tsvideos.core.domain.CleanupPlan
import me.pinfort.tsvideos.core.domain.CleanupTarget
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDateTime

class CleanupResourceMissing : IllegalArgumentException("An explicitly selected recording is missing")

@Component
class CleanupPlanCommand(
    private val duplicates: DuplicateReviewCommand,
    private val programs: ProgramMapper,
    private val bulk: BulkLibraryCommand,
    transactionManager: PlatformTransactionManager,
) {
    private val read =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    private val fingerprintPattern = Regex("[a-f0-9]{64}")

    fun plan(criteria: CleanupCriteria): CleanupPlan = read.execute { generate(criteria, Instant.now().toString()) }!!

    fun apply(
        plan: CleanupPlan,
        dryRun: Boolean = false,
    ): BulkResult {
        validate(plan)
        if (dryRun) {
            return read.execute {
                BulkResult(
                    plan.targets.map {
                        checkCurrent(plan, it.id, emptySet())
                            ?: BulkOutcome(
                                it.id,
                                BulkOutcomeStatus.SUCCESS,
                                "Validated against current state only; no changes. Earlier targets' deletions are not simulated",
                            )
                    },
                )
            }!!
        }
        if (plan.targets.isEmpty()) return BulkResult(emptyList())
        return bulk.applyGuarded(
            BulkApplyRequest(
                BulkRequest(plan.targets.map { it.id }, BulkAction.DELETE),
                plan.targets.associate { it.id to it.fingerprint },
            ),
        ) { id, completed -> checkCurrent(plan, id, completed) }
    }

    private fun checkCurrent(
        plan: CleanupPlan,
        id: Long,
        completed: Set<Long>,
    ): BulkOutcome? {
        val remainingTargets = plan.targets.filter { it.id !in completed }
        val allIds = programs.selectAllIds().toSet()
        if (id !in allIds) return BulkOutcome(id, BulkOutcomeStatus.NOT_FOUND, "Recording no longer exists")
        val keptMissing = plan.keptTargets.any { it.id !in allIds }
        if (keptMissing) {
            return BulkOutcome(id, BulkOutcomeStatus.STALE, "Selected kept recording changed; generate a new plan")
        }
        if (snapshot(plan.keptTargets.map { it.id }, "Selected duplicate copy to preserve") != plan.keptTargets) {
            return BulkOutcome(id, BulkOutcomeStatus.STALE, "Selected kept recording changed; generate a new plan")
        }
        val expectedGroups =
            plan.groups
                .map { it.copy(memberIds = it.memberIds.filter { member -> member !in completed }) }
                .filter { it.memberIds.size > 1 }
        val duplicateMode = plan.criteria.mode == CleanupMode.DUPLICATES
        val currentCriteria = if (duplicateMode) plan.criteria.copy(keepIds = expectedGroups.map { it.keepId }) else plan.criteria
        val fresh =
            try {
                generate(currentCriteria, plan.createdAt, allIds)
            } catch (error: IllegalArgumentException) {
                // Validation messages describe the rejected selection only, so they are safe to surface.
                return BulkOutcome(id, BulkOutcomeStatus.STALE, "Cleanup criteria or group changed (${error.message}); generate a new plan")
            }

        val expectedKeeps = plan.keptTargets.filter { keep -> expectedGroups.any { it.keepId == keep.id } }
        if (fresh.targets != remainingTargets || fresh.groups != expectedGroups || fresh.keptTargets != expectedKeeps) {
            return BulkOutcome(id, BulkOutcomeStatus.STALE, "Cleanup candidates, kept copy or file snapshots changed; generate a new plan")
        }
        val target =
            fresh.targets.firstOrNull { it.id == id }
                ?: return BulkOutcome(id, BulkOutcomeStatus.STALE, "Recording is no longer a cleanup candidate; generate a new plan")
        return if (!target.eligible) BulkOutcome(id, BulkOutcomeStatus.FAILED, "Recording is not eligible for cleanup") else null
    }

    private fun generate(
        criteria: CleanupCriteria,
        createdAt: String,
        known: Set<Long> = programs.selectAllIds().toSet(),
    ): CleanupPlan {
        validateCriteria(criteria)
        val groups: List<CleanupGroup>
        val ids: List<Long>
        when (criteria.mode) {
            CleanupMode.DUPLICATES -> {
                // DuplicateProgramCommand groups by channel, so every member shares the first member's channel.
                val review =
                    duplicates.review().groups.filter {
                        criteria.channel == null ||
                            it.members
                                .first()
                                .program.channel == criteria.channel
                    }
                if (criteria.keepIds.any { it !in known }) throw CleanupResourceMissing()
                require(
                    criteria.keepIds.all { keep ->
                        review.any { group ->
                            group.members.any { it.program.id == keep }
                        }
                    },
                ) { "Keep IDs must belong to duplicate groups" }
                groups =
                    review
                        .map { group ->
                            val members = group.members.map { it.program.id }.sorted()
                            val overrides = criteria.keepIds.filter { it in members }
                            require(overrides.size <= 1) { "Select one kept copy per group" }
                            CleanupGroup(members, overrides.singleOrNull() ?: group.recommendedId)
                        }.sortedBy { it.memberIds.first() }
                ids = groups.flatMap { it.memberIds - it.keepId }.sorted()
            }
            CleanupMode.RETENTION -> {
                groups = emptyList()
                val before = LocalDateTime.parse(criteria.recordedBefore!!)
                ids =
                    programs
                        .selectAll()
                        .filter {
                            it.recordedAt != null &&
                                it.recordedAt < before &&
                                (criteria.channel == null || it.channel == criteria.channel)
                        }.map { it.id }
                        .sorted()
            }
        }
        require(
            ids.size <= MAX_PLAN_SIZE && groups.size <= MAX_PLAN_SIZE,
        ) {
            "A cleanup plan supports at most $MAX_PLAN_SIZE targets/groups (found ${ids.size} targets, ${groups.size} groups); " +
                "narrow the explicit criteria"
        }
        val targets =
            snapshot(ids, "${criteria.mode.name} candidate").map { target ->
                val reason =
                    when (criteria.mode) {
                        CleanupMode.DUPLICATES -> {
                            val keepId = groups.single { target.id in it.memberIds }.keepId
                            "Duplicate broadcast candidate; preserve selected copy $keepId"
                        }
                        CleanupMode.RETENTION -> "Recorded before ${criteria.recordedBefore}; channel ${criteria.channel ?: "any"}"
                    }
                target.copy(reason = if (target.eligible) reason else "$reason; ${target.reason}")
            }
        val keeps = snapshot(groups.map { it.keepId }.sorted(), "Selected duplicate copy to preserve")
        return CleanupPlan(createdAt = createdAt, criteria = criteria, groups = groups, targets = targets, keptTargets = keeps)
    }

    private fun snapshot(
        ids: List<Long>,
        reason: String,
    ): List<CleanupTarget> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            bulk.preview(BulkRequest(ids, BulkAction.DELETE)).targets.map { target ->
                CleanupTarget(
                    target.id,
                    target.fingerprint,
                    if (target.eligible) reason else "$reason; ${target.reason}",
                    target.eligible,
                    target.estimatedMetadataBytes,
                    target.snapshot?.executed?.file,
                    target.snapshot?.splitFiles.orEmpty().map {
                        it.file
                    },
                    target.snapshot
                        ?.createdFiles
                        .orEmpty()
                        .map { CleanupFile(it.id, it.file, it.size, it.status.name) },
                )
            }
        }

    private fun validateCriteria(criteria: CleanupCriteria) {
        require(criteria.channel == null || criteria.channel.isNotBlank()) { "Channel must be nonblank" }
        require(
            criteria.keepIds.size <= MAX_PLAN_SIZE &&
                criteria.keepIds.all {
                    it > 0
                } &&
                criteria.keepIds.distinct().size == criteria.keepIds.size,
        ) { "Invalid keep IDs" }
        when (criteria.mode) {
            CleanupMode.DUPLICATES -> require(criteria.recordedBefore == null) { "Duplicate mode does not accept age criteria" }
            CleanupMode.RETENTION -> {
                require(
                    criteria.keepIds.isEmpty() && criteria.recordedBefore != null,
                ) { "Retention requires an explicit cutoff and no keep IDs" }
                LocalDateTime.parse(criteria.recordedBefore)
            }
        }
    }

    private fun validate(plan: CleanupPlan) {
        require(plan.schemaVersion == 1 && plan.action == "DELETE") { "Unsupported cleanup plan" }
        Instant.parse(plan.createdAt)
        validateCriteria(plan.criteria)
        val ids = plan.targets.map { it.id }
        val keeps = plan.keptTargets.map { it.id }
        require(
            ids.size <= MAX_PLAN_SIZE &&
                keeps.size <= MAX_PLAN_SIZE &&
                (ids + keeps).all { it > 0 } &&
                ids.distinct().size == ids.size &&
                keeps.distinct().size == keeps.size &&
                ids.intersect(keeps.toSet()).isEmpty(),
        ) { "Invalid plan IDs" }
        require(
            (plan.targets + plan.keptTargets).all {
                it.fingerprint.matches(fingerprintPattern) && it.estimatedMetadataBytes >= 0
            },
        ) { "Invalid snapshots" }
        require(plan.groups.size <= MAX_PLAN_SIZE) { "Invalid groups" }
        when (plan.criteria.mode) {
            CleanupMode.RETENTION -> require(plan.groups.isEmpty() && keeps.isEmpty()) { "Invalid retention keeps" }
            CleanupMode.DUPLICATES -> {
                require(plan.groups.map { it.keepId }.sorted() == keeps.sorted()) { "Invalid selected keeps" }
                require(plan.criteria.keepIds.all { it in keeps }) { "Keep criteria and selected keeps differ" }
                require(plan.groups.flatMap { it.memberIds }.sorted() == (ids + keeps).sorted()) { "Invalid group membership" }
            }
        }
        require(
            plan.groups.all {
                it.memberIds.size > 1 &&
                    it.memberIds.distinct().size == it.memberIds.size &&
                    it.keepId in it.memberIds &&
                    it.memberIds.all { member -> member in ids || member in keeps }
            },
        ) { "Invalid groups" }
    }

    companion object {
        /** Matches BulkLibraryCommand's per-request limit; there is no implicit batching. */
        const val MAX_PLAN_SIZE = 100
    }
}
