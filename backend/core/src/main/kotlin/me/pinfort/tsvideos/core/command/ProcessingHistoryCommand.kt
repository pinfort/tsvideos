package me.pinfort.tsvideos.core.command

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
import org.slf4j.Logger
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
    private val logger: Logger,
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
        transaction.execute {
            val stage = checkNotNull(mapper.stage(stageId)) { "Stage does not exist" }
            check(mapper.lockAttempt(stage.attemptId)?.outcome == ProcessingOutcome.ACTIVE) { "Attempt is not active" }
            check(mapper.finishStage(stageId, outcome.name, error?.name, artifact?.type?.name, artifact?.id, artifact?.path) == 1) {
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

    /**
     * Progress heartbeats are throttled independently from recording timestamps. They are best effort:
     * history is evidence, not authority, so a failed heartbeat must not abort the compress/upload it observes.
     */
    fun progress(
        attemptId: Long,
        listener: (Long, Long) -> Unit,
        clock: () -> Long = System::nanoTime,
    ): (Long, Long) -> Unit {
        val last = AtomicLong(NEVER)
        return { value, total ->
            val now = clock()
            val previous = last.get()
            if ((previous == NEVER || now - previous >= HEARTBEAT_INTERVAL_NANOS) && last.compareAndSet(previous, now)) {
                try {
                    heartbeat(attemptId)
                } catch (e: Exception) {
                    logger.warn("Recording progress heartbeat of attempt $attemptId failed; continuing", e)
                }
            }
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
        val result =
            try {
                operation()
            } catch (failure: Exception) {
                val code = classify(failure)
                try {
                    withInterruptCleared { finishStage(event, outcome(code), code) }
                } catch (historyFailure: Exception) {
                    failure.addSuppressed(historyFailure)
                }
                throw failure
            }
        // The work succeeded, so a failure below is a bookkeeping failure and must not record the stage as FAILED.
        finishStage(event, ProcessingOutcome.COMPLETED, artifact = artifact(result))
        return result
    }

    fun failed(
        attemptId: Long,
        failure: Exception,
    ) {
        val code = classify(failure)
        withInterruptCleared { finish(attemptId, outcome(code), code) }
    }

    fun history(jobId: Long? = null): List<ProcessingAttemptHistory> =
        checkNotNull(
            readTransaction.execute {
                require(jobId == null || jobId > 0)
                val identities = mapper.identities(jobId).associateBy { it.jobId }
                val attempts = mapper.attempts(jobId)
                // Chunked so that --all over a large history stays well under max_allowed_packet.
                val stages =
                    attempts
                        .map { it.id }
                        .chunked(STAGE_CHUNK)
                        .flatMap { mapper.stagesOf(it) }
                        .groupBy { it.attemptId }
                attempts.map { ProcessingAttemptHistory(checkNotNull(identities[it.jobId]), it, stages[it.id].orEmpty()) }
            },
        )

    fun status(jobId: Long? = null): List<ProcessingStatus> =
        checkNotNull(
            readTransaction.execute {
                require(jobId == null || jobId > 0)
                val identities = mapper.identities(jobId).associateBy { it.jobId }
                val jobs = mapper.liveJobs(jobId).associateBy { it.id }
                val attempts = mapper.attempts(jobId).groupBy { it.jobId }
                (identities.keys + jobs.keys).sorted().map { id ->
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

    /**
     * An interrupted thread can make the JDBC write itself fail, which would leave the stage/attempt ACTIVE forever.
     * Clear the flag for the closing write only and restore it so the caller still observes the interruption.
     */
    private fun <T> withInterruptCleared(write: () -> T): T {
        val interrupted = Thread.interrupted()
        try {
            return write()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun outcome(code: ProcessingErrorCode) =
        if (code == ProcessingErrorCode.INTERRUPTED) ProcessingOutcome.INTERRUPTED else ProcessingOutcome.FAILED

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

    private companion object {
        const val HEARTBEAT_INTERVAL_NANOS = 10_000_000_000L

        // System.nanoTime() may return any value, including 0, so "no heartbeat yet" needs its own sentinel.
        const val NEVER = Long.MIN_VALUE
        const val STAGE_CHUNK = 1000
    }
}
