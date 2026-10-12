package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingJobMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Short committed DB operations: never hold a transaction across an external side effect. */
@Component
class ProcessingJobCommand(
    private val mapper: ProcessingJobMapper,
) {
    @Transactional
    fun claim(identity: ProcessingIdentity): ProcessingJob? {
        if (mapper.claim(identity.key, identity.path, identity.size, identity.modifiedMillis) != 1 &&
            mapper.retry(identity.key) != 1
        ) {
            return null
        }
        return checkNotNull(mapper.find(identity.key))
    }

    fun find(identity: ProcessingIdentity): ProcessingJob? = mapper.find(identity.key)

    fun findByExecutedFile(id: Long): ProcessingJob? = mapper.findByExecutedFile(id)

    fun findByPath(path: String): ProcessingJob? = mapper.findByPath(path)

    @Transactional
    fun link(
        job: ProcessingJob,
        executedId: Long,
    ) {
        check(mapper.link(job.id, executedId) == 1)
    }

    @Transactional
    fun state(
        job: ProcessingJob,
        state: String,
    ) {
        check(mapper.state(job.id, state) == 1)
    }

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
        check(mapper.releaseCallback(job.id, token) == 1)
    }

    @Transactional
    fun freezeManifest(
        job: ProcessingJob,
        token: String,
        manifest: String,
    ) {
        check(mapper.manifest(job.id, token, manifest) == 1) { "Callback manifest changed; reconciliation required" }
    }

    /** Operator must first inspect the encoder queue. No timeout is evidence of rejection. */
    @Transactional
    fun reconcileSubmission(
        job: ProcessingJob,
        accepted: Boolean,
    ) {
        check(mapper.reconcile(job.id, "SUBMISSION_UNKNOWN", if (accepted) "SUBMITTED" else "REJECTED") == 1)
    }

    @Transactional
    fun reset(executedId: Long) {
        val job = mapper.findByExecutedFile(executedId) ?: return
        check(job.callbackClaim == null && job.state in setOf("REJECTED", "COMPLETED", "RETRYABLE", "RESETTING")) {
            "Encoder may still be using these files; reconcile before reset"
        }
        check(mapper.removeReset(executedId) == 1)
    }

    @Transactional
    fun prepareReset(executedId: Long) {
        val job = mapper.findByExecutedFile(executedId) ?: return
        check(mapper.prepareReset(job.id) == 1) { "Active processing or encoder submission must be reconciled before reset" }
    }
}
