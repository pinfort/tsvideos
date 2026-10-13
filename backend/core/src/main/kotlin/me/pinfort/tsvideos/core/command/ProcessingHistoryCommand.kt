package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.EncodingProfileSelection
import me.pinfort.tsvideos.core.domain.ProcessingArtifact
import me.pinfort.tsvideos.core.domain.ProcessingAttempt
import me.pinfort.tsvideos.core.domain.ProcessingAttemptHistory
import me.pinfort.tsvideos.core.domain.ProcessingAttemptKind
import me.pinfort.tsvideos.core.domain.ProcessingErrorCode
import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.domain.ProcessingOutcome
import me.pinfort.tsvideos.core.domain.ProcessingStage
import me.pinfort.tsvideos.core.domain.ProcessingStatus
import me.pinfort.tsvideos.core.external.database.mapper.GeneratedKeyHolder
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingHistoryMapper
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicLong

/** Durable diagnostic history. Every DB transaction ends before invoking external work. */
@Component
class ProcessingHistoryCommand(
    private val mapper: ProcessingHistoryMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    private val readTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            isReadOnly = true
        }

    fun begin(
        job: ProcessingJob,
        kind: ProcessingAttemptKind,
    ): ProcessingAttempt =
        checkNotNull(
            transaction.execute {
                mapper.retain(job)
                val identity = checkNotNull(mapper.lockIdentity(job.id))
                check(
                    identity.identityKey == job.identityKey &&
                        identity.inputPath == job.inputPath &&
                        identity.inputSize == job.inputSize &&
                        identity.modifiedMillis == job.modifiedMillis,
                ) { "History identity conflict" }
                check(mapper.increment(job.id) == 1)
                val key = GeneratedKeyHolder()
                mapper.begin(job.id, Math.addExact(identity.lastAttempt, 1), kind.name, key)
                checkNotNull(mapper.attempt(key.id))
            },
        )

    fun startStage(
        attemptId: Long,
        stage: ProcessingStage,
    ): Long =
        checkNotNull(
            transaction.execute {
                check(mapper.lockAttempt(attemptId)?.outcome == ProcessingOutcome.ACTIVE) { "Attempt is not active" }
                check(mapper.stages(attemptId).none { it.outcome == ProcessingOutcome.ACTIVE }) { "An attempt already has an active stage" }
                val key = GeneratedKeyHolder()
                mapper.startStage(attemptId, stage.name, key)
                check(mapper.heartbeat(attemptId) == 1)
                key.id
            },
        )

    fun finishStage(
        stageId: Long,
        outcome: ProcessingOutcome,
        error: ProcessingErrorCode? = null,
        artifact: ProcessingArtifact? = null,
    ) {
        require(outcome != ProcessingOutcome.ACTIVE)
        require((outcome == ProcessingOutcome.COMPLETED) == (error == null))
        require(artifact == null || artifact.type in setOf("INPUT", "SPLITTED_FILE", "UPLOAD_MANIFEST", "PROGRAM"))
        transaction.execute {
            val stage = checkNotNull(mapper.stage(stageId)) { "Stage does not exist" }
            check(mapper.lockAttempt(stage.attemptId)?.outcome == ProcessingOutcome.ACTIVE) { "Attempt is not active" }
            check(mapper.finishStage(stageId, outcome.name, error?.name, artifact?.type, artifact?.id, artifact?.path) == 1) {
                "Stage was already finished"
            }
            check(mapper.heartbeat(stage.attemptId) == 1)
        }
    }

    fun finish(
        attemptId: Long,
        outcome: ProcessingOutcome,
        error: ProcessingErrorCode? = null,
    ) {
        require(outcome != ProcessingOutcome.ACTIVE)
        require((outcome == ProcessingOutcome.COMPLETED) == (error == null))
        transaction.execute {
            check(mapper.lockAttempt(attemptId)?.outcome == ProcessingOutcome.ACTIVE) { "Attempt is not active" }
            val active = mapper.stages(attemptId).filter { it.outcome == ProcessingOutcome.ACTIVE }
            check(outcome != ProcessingOutcome.COMPLETED || active.isEmpty()) { "Cannot complete an active stage" }
            active.forEach { check(mapper.finishStage(it.id, outcome.name, error?.name, null, null, null) == 1) }
            check(mapper.finish(attemptId, outcome.name, error?.name) == 1)
        }
    }

    fun heartbeat(attemptId: Long) {
        transaction.execute { check(mapper.heartbeat(attemptId) == 1) { "Attempt is not active" } }
    }

    /** Progress heartbeats are throttled independently from recording timestamps. */
    fun progress(
        attemptId: Long,
        listener: (Long, Long) -> Unit,
    ): (Long, Long) -> Unit {
        val last = AtomicLong(0)
        return { value, total ->
            val now = System.nanoTime()
            val previous = last.get()
            if ((previous == 0L || now - previous >= 10_000_000_000L) && last.compareAndSet(previous, now)) heartbeat(attemptId)
            listener(value, total)
        }
    }

    fun <T> stage(
        attemptId: Long,
        stage: ProcessingStage,
        artifact: (T) -> ProcessingArtifact? = { null },
        operation: () -> T,
    ): T {
        val event = startStage(attemptId, stage)
        try {
            val result = operation()
            finishStage(event, ProcessingOutcome.COMPLETED, artifact = artifact(result))
            return result
        } catch (failure: Exception) {
            try {
                finishStage(event, outcome(failure), classify(failure))
            } catch (
                historyFailure: Exception,
            ) {
                failure.addSuppressed(historyFailure)
            }
            throw failure
        }
    }

    fun failed(
        attemptId: Long,
        failure: Exception,
    ) {
        finish(attemptId, outcome(failure), classify(failure))
    }

    /** One immutable selection per process attempt, committed before encoder submission. */
    fun recordProfile(
        attemptId: Long,
        selection: EncodingProfileSelection,
    ) {
        require(selection.reason in setOf("MATCHED_RULE", "FALLBACK", "LEGACY_ATX_DIV", "LEGACY_DEFAULT"))
        require(selection.profile.isNotBlank() && selection.profile.length <= 255 && selection.profile.none(Char::isISOControl))
        require(selection.ruleId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
        transaction.execute {
            val attempt = checkNotNull(mapper.lockAttempt(attemptId))
            check(attempt.outcome == ProcessingOutcome.ACTIVE && attempt.kind == ProcessingAttemptKind.PROCESS) {
                "Profile selection requires an active process attempt"
            }
            check(mapper.insertProfile(attemptId, selection) == 1)
        }
    }

    fun history(jobId: Long? = null): List<ProcessingAttemptHistory> =
        checkNotNull(
            readTransaction.execute {
                require(jobId == null || jobId > 0)
                val identities = mapper.identities().associateBy { it.jobId }
                mapper.attempts().filter { jobId == null || it.jobId == jobId }.map {
                    ProcessingAttemptHistory(checkNotNull(identities[it.jobId]), it, mapper.stages(it.id), mapper.profile(it.id))
                }
            },
        )

    fun status(jobId: Long? = null): List<ProcessingStatus> =
        checkNotNull(
            readTransaction.execute {
                require(jobId == null || jobId > 0)
                val identities = mapper.identities().associateBy { it.jobId }
                val jobs = mapper.liveJobs().associateBy { it.id }
                val attempts = mapper.attempts().groupBy { it.jobId }
                (identities.keys + jobs.keys).sorted().filter { jobId == null || jobId == it }.map { id ->
                    val job = jobs[id]
                    val identity = identities[id]
                    val history = attempts[id].orEmpty()
                    ProcessingStatus(
                        id,
                        job?.identityKey ?: checkNotNull(identity).identityKey,
                        job?.inputPath ?: checkNotNull(identity).inputPath,
                        job?.state ?: "RETIRED",
                        history.lastOrNull(),
                        history.filter { it.outcome == ProcessingOutcome.ACTIVE },
                    )
                }
            },
        )

    private fun outcome(failure: Exception) =
        if (classify(failure) ==
            ProcessingErrorCode.INTERRUPTED
        ) {
            ProcessingOutcome.INTERRUPTED
        } else {
            ProcessingOutcome.FAILED
        }

    private fun classify(failure: Exception): ProcessingErrorCode {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
        return when {
            Thread.currentThread().isInterrupted || causes.any { it is InterruptedException } -> ProcessingErrorCode.INTERRUPTED
            causes.any { it is DataAccessException || it is SQLException } -> ProcessingErrorCode.DATABASE_FAILURE
            causes.any { it is IOException } -> ProcessingErrorCode.IO_FAILURE
            causes.any { it is IllegalArgumentException } -> ProcessingErrorCode.INVALID_INPUT
            else -> ProcessingErrorCode.OPERATION_FAILED
        }
    }
}
