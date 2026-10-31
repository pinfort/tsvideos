package me.pinfort.tsvideos.library.api

import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import me.pinfort.tsvideos.library.domain.DuplicateGroupIdentity
import me.pinfort.tsvideos.library.domain.DuplicateRecommendation
import me.pinfort.tsvideos.library.domain.DuplicateReview
import me.pinfort.tsvideos.library.domain.DuplicateReviewGroup
import me.pinfort.tsvideos.library.domain.DuplicateReviewMember
import me.pinfort.tsvideos.library.domain.StaleDuplicateGroupException
import me.pinfort.tsvideos.library.persistence.DuplicateDismissalMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
class DuplicateReviewCommand internal constructor(
    private val duplicates: DuplicateProgramCommand,
    private val files: CreatedFileCatalog,
    private val dismissals: DuplicateDismissalMapper,
) {
    /**
     * Detected groups enriched for review. [includeHidden] decides whether groups an operator hid as intentional are
     * returned (each with `hidden = true`); the web review leaves them out by default, cleanup plans keep them all.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun review(includeHidden: Boolean): DuplicateReview {
        val hiddenKeys = dismissals.keys().toSet()
        val groups =
            duplicates
                .detect()
                .map { it to (key(it) in hiddenKeys) }
                .filter { (_, hidden) -> includeHidden || !hidden }
        // Batched instead of one query per member.
        val movedFiles = files.movedFilesByRecording(groups.flatMap { (group, _) -> group.programs.map { it.executedFileId } })
        return DuplicateReview(
            groups.map { (group, hidden) ->
                // DuplicateGroup guarantees 2+ members, sorted by keep order so the recommended one comes first.
                val winner = group.recommended
                val runnerUp = group.programs[1]
                DuplicateReviewGroup(
                    group.reason,
                    winner.id,
                    DuplicateRecommendation(
                        DuplicateProgramCommand.keepCriteria.map { it.first },
                        DuplicateProgramCommand.decisiveCriterion(winner, runnerUp),
                        runnerUp.id,
                        winner.drops.takeIf { it >= 0 },
                        winner.status,
                        winner.duration,
                        winner.size,
                        winner.id,
                    ),
                    group.programs.map { program ->
                        DuplicateReviewMember(program, movedFiles[program.executedFileId].orEmpty())
                    },
                    hidden,
                )
            },
        )
    }

    /** Hides [identity] from the default review. Idempotent; never touches recordings or files. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun hide(identity: DuplicateGroupIdentity) {
        val ids = detected(identity)
        dismissals.insert(identity.reason.name, ids, LocalDateTime.now(ZoneOffset.UTC))
    }

    /** Shows [identity] in the default review again. Idempotent. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun restore(identity: DuplicateGroupIdentity) {
        val ids = detected(identity)
        dismissals.delete(identity.reason.name, ids)
    }

    /** Validates [identity] against the groups detected now and returns its canonical member list. */
    private fun detected(identity: DuplicateGroupIdentity): String {
        val ids = identity.programIds
        require(ids.size >= 2) { "A duplicate group has at least two program IDs" }
        require(ids.all { it > 0 }) { "Program IDs must be positive" }
        require(ids.toSet().size == ids.size) { "Program IDs must not repeat" }
        val canonical = canonical(ids)
        if (duplicates.detect().none { key(it) == "${identity.reason.name}:$canonical" }) throw StaleDuplicateGroupException()
        return canonical
    }

    private companion object {
        fun canonical(ids: List<Long>): String = ids.sorted().joinToString(",")

        // Must match CONCAT(reason,':',program_ids) in DuplicateDismissalMapper.
        fun key(group: DuplicateGroup): String = "${group.reason.name}:${canonical(group.programs.map { it.id })}"
    }
}
