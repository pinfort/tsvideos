package me.pinfort.tsvideos.processing.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.processing.domain.ProcessingIdentity
import me.pinfort.tsvideos.processing.domain.ProcessingJob
import me.pinfort.tsvideos.processing.domain.ProcessingJob.State
import me.pinfort.tsvideos.processing.persistence.ProcessingJobMapper
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException

class ProcessingJobCommandTest :
    ExpectSpec({
        val identity = ProcessingIdentity("key", "/source", 1, 2)
        val job = ProcessingJob(1, "key", "/source", 1, 2, State.CLAIMED, null, null)
        expect("non-duplicate database failures propagate without attempting retry") {
            val mapper = mockk<ProcessingJobMapper>()
            every { mapper.claim(any(), any(), any(), any(), any()) } throws DataIntegrityViolationException("invalid data")
            shouldThrow<DataIntegrityViolationException> { ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper)).claim(identity) }
            verify(exactly = 0) { mapper.retry(any(), any(), any()) }
        }
        expect("only duplicate-key failure enters the retry CAS") {
            val mapper = mockk<ProcessingJobMapper>()
            every { mapper.claim(any(), any(), any(), any(), State.CLAIMED) } throws DuplicateKeyException("duplicate")
            every { mapper.retry("key", State.RETRYABLE, State.CLAIMED) } returnsMany listOf(0, 1)
            every { mapper.find("key") } returns job
            val command = ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper))
            command.claim(identity) shouldBe null
            command.claim(identity) shouldBe job
        }
        expect("only the generic transitions are allowed") {
            val pairs = State.entries.flatMap { from -> State.entries.map { from to it } }
            val allowed = pairs.filter { (from, to) -> from.canTransitionTo(to) }
            val expected = listOf(State.CLAIMED to State.RETRYABLE, State.CLAIMED to State.SUBMISSION_UNKNOWN)
            allowed shouldBe expected
        }
        expect("unsupported transitions are rejected before database mutation") {
            val mapper = mockk<ProcessingJobMapper>()
            val command = ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper))
            listOf(
                State.COMPLETED to State.CLAIMED,
                State.CLAIMED to State.SUBMITTED,
                State.SUBMISSION_UNKNOWN to State.RETRYABLE,
                State.CLAIMED to State.QUARANTINED,
            ).forEach { (from, to) ->
                shouldThrow<IllegalArgumentException> { command.transition(job, from, to) }
            }
            verify(exactly = 0) { mapper.transition(any(), any(), any()) }
        }
        expect("stale state fails instead of overwriting a newer state") {
            val mapper = mockk<ProcessingJobMapper>()
            every { mapper.transition(1, State.CLAIMED, State.SUBMISSION_UNKNOWN) } returns 0
            shouldThrow<IllegalStateException> {
                ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper)).transition(job, State.CLAIMED, State.SUBMISSION_UNKNOWN)
            }
        }
        expect("completed callback wins over delayed acknowledgement") {
            val mapper = mockk<ProcessingJobMapper>()
            every { mapper.transition(1, State.SUBMISSION_UNKNOWN, State.SUBMITTED) } returns 0
            every { mapper.find("key") } returns job.copy(state = State.COMPLETED)
            ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper)).acknowledgeSubmission(job)
            verify(exactly = 0) { mapper.transition(1, State.COMPLETED, any()) }
        }
        expect("completion handles acknowledgement racing the callback") {
            val mapper = mockk<ProcessingJobMapper>()
            every { mapper.transition(1, State.SUBMISSION_UNKNOWN, State.COMPLETED) } returns 0
            every { mapper.transition(1, State.SUBMITTED, State.COMPLETED) } returns 1
            ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper)).complete(job)
        }
        expect("acknowledgement and completion reject unrelated lifecycle states") {
            val mapper = mockk<ProcessingJobMapper>()
            every { mapper.transition(any(), any(), any()) } returns 0
            every { mapper.find("key") } returns job.copy(state = State.RESETTING)
            shouldThrow<IllegalStateException> {
                ProcessingJobCommand(
                    mapper,
                    ProcessingJobClaimCommand(mapper),
                ).acknowledgeSubmission(job)
            }
            shouldThrow<IllegalStateException> { ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper)).complete(job) }
        }
        expect("reset refuses states in which the encoder or an invocation may still use the files") {
            val mapper = mockk<ProcessingJobMapper>()
            val command = ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper))
            listOf(State.CLAIMED, State.QUARANTINED, State.SUBMISSION_UNKNOWN, State.SUBMITTED).forEach { state ->
                every { mapper.findByExecutedFile(10) } returns job.copy(state = state, executedFileId = 10)
                shouldThrow<IllegalStateException> { command.reset(10) }
            }
            verify(exactly = 0) { mapper.removeReset(any(), any()) }
        }
    })
