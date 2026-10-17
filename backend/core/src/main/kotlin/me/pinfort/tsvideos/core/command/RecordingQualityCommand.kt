package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.ProcessingErrorCode
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.domain.ProcessingOutcome
import me.pinfort.tsvideos.core.domain.QualityAction
import me.pinfort.tsvideos.core.domain.RecordingQualityDecision
import me.pinfort.tsvideos.core.domain.RecordingQualityReport
import me.pinfort.tsvideos.core.exception.QualityReleaseRefusedException
import me.pinfort.tsvideos.core.exception.QualityReleaseRefusedException.Reason
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingHistoryMapper
import me.pinfort.tsvideos.core.external.database.mapper.RecordingQualityMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.io.File

@Component
class RecordingQualityCommand(
    private val mapper: RecordingQualityMapper,
    private val historyMapper: ProcessingHistoryMapper,
) {
    private val json = JsonMapper.builder().build()

    @Transactional
    fun record(
        job: ProcessingJob,
        attemptId: Long,
        decision: RecordingQualityDecision,
    ) {
        val current = checkNotNull(mapper.lockJob(job.id)) { "Processing job not found" }
        check(current.identityKey == job.identityKey && current.state == "CLAIMED" && current.executedFileId == null) {
            "Quality checks require an owned unregistered job"
        }
        check(mapper.ownedAttempt(attemptId, job.id) == 1) { "Quality attempt is not active" }
        check(
            mapper.insert(attemptId, job.id, decision.action, job.inputPath, job.identityKey, json.writeValueAsString(decision.findings)) ==
                1,
        ) { "Quality report for attempt $attemptId was not recorded" }
        if (decision.action != QualityAction.QUARANTINE) return
        check(mapper.transition(job.id, "CLAIMED", "QUARANTINED") == 1) { "Job ${job.id} could not be quarantined" }
        // Same transaction as the transition: a committed quarantine never leaves an ACTIVE attempt blocking release.
        check(
            historyMapper.finish(attemptId, ProcessingOutcome.FAILED.name, ProcessingErrorCode.QUALITY_QUARANTINED.name) == 1,
        ) { "Quality attempt $attemptId could not be finished" }
    }

    fun reports(jobId: Long): List<RecordingQualityReport> {
        require(jobId > 0)
        return mapper.forJob(jobId)
    }

    /** In-place hold is released only explicitly, after the originating invocation is terminal. */
    @Transactional
    fun release(
        jobId: Long,
        dryRun: Boolean = false,
    ) {
        require(jobId > 0)
        val job = checkNotNull(mapper.lockJob(jobId)) { "Processing job not found" }
        refuseUnless(job.state == "QUARANTINED" && job.executedFileId == null && job.callbackClaim == null, Reason.NOT_QUARANTINED)
        refuseUnless(mapper.activeAttempts(jobId) == 0, Reason.ACTIVE_ATTEMPT)
        val source = File(job.inputPath)
        refuseUnless(source.isFile && ProcessingIdentity.from(source).key == job.identityKey, Reason.SOURCE_CHANGED)
        if (dryRun) return
        refuseUnless(mapper.transition(jobId, "QUARANTINED", "RETRYABLE") == 1, Reason.CHANGED_CONCURRENTLY)
    }

    private fun refuseUnless(
        condition: Boolean,
        reason: Reason,
    ) {
        if (!condition) throw QualityReleaseRefusedException(reason)
    }
}
