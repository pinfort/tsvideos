package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.domain.QualityAction
import me.pinfort.tsvideos.core.domain.RecordingQualityDecision
import me.pinfort.tsvideos.core.domain.RecordingQualityReport
import me.pinfort.tsvideos.core.external.database.mapper.RecordingQualityMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.io.File

@Component
class RecordingQualityCommand(
    private val mapper: RecordingQualityMapper,
) {
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
            mapper.insert(
                RecordingQualityReport(
                    attemptId,
                    job.id,
                    "",
                    decision.action,
                    job.inputPath,
                    job.identityKey,
                    JsonMapper.builder().build().writeValueAsString(decision.findings),
                ),
            ) == 1,
        )
        if (decision.action == QualityAction.QUARANTINE) check(mapper.transition(job.id, "CLAIMED", "QUARANTINED") == 1)
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
        check(job.state == "QUARANTINED" && job.executedFileId == null && job.callbackClaim == null) { "Job is not quarantined" }
        check(mapper.activeAttempts(jobId) == 0) { "Quality invocation is still active" }
        val source = File(job.inputPath)
        check(source.isFile && ProcessingIdentity.from(source).key == job.identityKey) { "Quarantined source changed or is missing" }
        if (dryRun) return
        check(mapper.transition(jobId, "QUARANTINED", "RETRYABLE") == 1) { "Job changed" }
    }
}
