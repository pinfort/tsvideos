package me.pinfort.tsvideos.processing.api

import me.pinfort.tsvideos.processing.domain.ProcessingIdentity
import me.pinfort.tsvideos.processing.domain.ProcessingJob
import me.pinfort.tsvideos.processing.persistence.ProcessingJobMapper
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Short committed DB operations: never hold a transaction across an external side effect. */
@Component
class ProcessingJobCommand internal constructor(
    private val mapper: ProcessingJobMapper,
    private val claims: ProcessingJobClaimCommand,
) {
    /**
     * Claims a new job, or re-claims a RETRYABLE one.
     * @return the claimed job, or null when the claim was not acquired: another invocation holds it,
     *   or the existing job is not RETRYABLE.
     */
    fun claim(identity: ProcessingIdentity): ProcessingJob? =
        try {
            claims.insert(identity)
        } catch (duplicate: DuplicateKeyException) {
            claims.retry(identity)
        }

    fun find(identity: ProcessingIdentity): ProcessingJob? = mapper.find(identity.key)

    fun findByExecutedFile(id: Long): ProcessingJob? = mapper.findByExecutedFile(id)

    fun findByPath(path: String): ProcessingJob? = mapper.findByPath(path)

    @Transactional
    fun link(
        job: ProcessingJob,
        executedId: Long,
    ) {
        check(mapper.link(job.id, executedId) == 1) {
            "Cannot link job ${job.id} to executed file $executedId; it is no longer CLAIMED or already linked"
        }
    }

    @Transactional
    fun transition(
        job: ProcessingJob,
        expected: String,
        next: String,
    ) {
        require(expected to next in allowedTransitions) { "Unsupported processing job transition: $expected -> $next" }
        check(mapper.transition(job.id, expected, next) == 1) {
            "Processing job ${job.id} is no longer $expected; reconcile before proceeding"
        }
    }

    /** A fast callback may complete before the encoder client's acknowledgement is recorded. */
    @Transactional
    fun acknowledgeSubmission(job: ProcessingJob) {
        if (mapper.transition(job.id, "SUBMISSION_UNKNOWN", "SUBMITTED") == 1) return
        check(mapper.find(job.identityKey)?.state in setOf("SUBMITTED", "COMPLETED")) {
            "Submission acknowledgement for job ${job.id} conflicts with its current state"
        }
    }

    @Transactional
    fun complete(job: ProcessingJob) {
        // Try both legitimate predecessor states; submission acknowledgement can race this callback.
        if (mapper.transition(job.id, "SUBMISSION_UNKNOWN", "COMPLETED") == 1) return
        if (mapper.transition(job.id, "SUBMITTED", "COMPLETED") == 1) return
        val state = mapper.find(job.identityKey)?.state
        check(state == "COMPLETED") { "Cannot complete job ${job.id} in state $state" }
    }

    private val allowedTransitions =
        setOf(
            "CLAIMED" to "RETRYABLE",
            "CLAIMED" to "SUBMISSION_UNKNOWN",
        )

    @Transactional
    fun claimCallback(job: ProcessingJob): String? {
        val token = UUID.randomUUID().toString()
        return token.takeIf { mapper.claimCallback(job.id, token) == 1 }
    }

    @Transactional
    fun releaseCallback(
        job: ProcessingJob,
        token: String,
    ) {
        check(mapper.releaseCallback(job.id, token) == 1) {
            "Callback claim of job ${job.id} was not held by this callback; reconcile the stale claim"
        }
    }

    @Transactional
    fun freezeManifest(
        job: ProcessingJob,
        token: String,
        manifest: String,
    ) {
        check(mapper.manifest(job.id, token, manifest) == 1) { "Callback manifest of job ${job.id} changed; reconciliation required" }
    }

    /** Operator must first inspect the encoder queue. No timeout is evidence of rejection. */
    @Transactional
    fun reconcileSubmission(
        job: ProcessingJob,
        accepted: Boolean,
    ) {
        check(mapper.reconcile(job.id, "SUBMISSION_UNKNOWN", if (accepted) "SUBMITTED" else "REJECTED") == 1) {
            "Job ${job.id} is no longer an unclaimed SUBMISSION_UNKNOWN job"
        }
    }

    @Transactional
    fun reset(executedId: Long) {
        val job = mapper.findByExecutedFile(executedId) ?: return
        check(job.callbackClaim == null && job.state in setOf("REJECTED", "COMPLETED", "RETRYABLE", "RESETTING")) {
            "Job ${job.id} is ${job.state} (callback claim ${job.callbackClaim}); encoder may still be using these files, reconcile before reset"
        }
        check(mapper.removeReset(executedId) == 1) { "Job ${job.id} changed during reset; reconcile before retrying" }
    }

    @Transactional
    fun prepareReset(executedId: Long) {
        val job = mapper.findByExecutedFile(executedId) ?: return
        check(mapper.prepareReset(job.id) == 1) {
            "Job ${job.id} is ${job.state}; active processing or encoder submission must be reconciled before reset"
        }
    }
}
