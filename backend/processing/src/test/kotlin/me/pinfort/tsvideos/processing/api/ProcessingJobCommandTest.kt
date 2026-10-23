package me.pinfort.tsvideos.processing.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.processing.domain.ProcessingIdentity
import me.pinfort.tsvideos.processing.domain.ProcessingJob
import me.pinfort.tsvideos.processing.persistence.ProcessingJobMapper
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException

class ProcessingJobCommandTest :
    ExpectSpec({
        val identity = ProcessingIdentity("key", "/source", 1, 2)
        val job = ProcessingJob(1, "key", "/source", 1, 2, "CLAIMED", null, null)
        expect("non-duplicate database failures propagate without attempting retry") {
            val mapper = mockk<ProcessingJobMapper>()
            every { mapper.claim(any(), any(), any(), any()) } throws DataIntegrityViolationException("invalid data")
            shouldThrow<DataIntegrityViolationException> { ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper)).claim(identity) }
            verify(exactly = 0) { mapper.retry(any()) }
        }
        expect("only duplicate-key failure enters the retry CAS") {
            val mapper = mockk<ProcessingJobMapper>()
            every { mapper.claim(any(), any(), any(), any()) } throws DuplicateKeyException("duplicate")
            every { mapper.retry("key") } returnsMany listOf(0, 1)
            every { mapper.find("key") } returns job
            val command = ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper))
            command.claim(identity) shouldBe null
            command.claim(identity) shouldBe job
        }
        expect("unsupported transitions are rejected before database mutation") {
            val mapper = mockk<ProcessingJobMapper>()
            val command = ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper))
            listOf("COMPLETED" to "CLAIMED", "CLAIMED" to "SUBMITTED", "SUBMISSION_UNKNOWN" to "RETRYABLE").forEach { (from, to) ->
                shouldThrow<IllegalArgumentException> { command.transition(job, from, to) }
            }
            verify(exactly = 0) { mapper.transition(any(), any(), any()) }
        }
        expect("stale state fails instead of overwriting a newer state") {
            val mapper = mockk<ProcessingJobMapper>()
            every { mapper.transition(1, "CLAIMED", "SUBMISSION_UNKNOWN") } returns 0
            shouldThrow<IllegalStateException> {
                ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper)).transition(job, "CLAIMED", "SUBMISSION_UNKNOWN")
            }
        }
        expect("completed callback wins over delayed acknowledgement") {
            val mapper = mockk<ProcessingJobMapper>()
            every { mapper.transition(1, "SUBMISSION_UNKNOWN", "SUBMITTED") } returns 0
            every { mapper.find("key") } returns job.copy(state = "COMPLETED")
            ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper)).acknowledgeSubmission(job)
            verify(exactly = 0) { mapper.transition(1, "COMPLETED", any()) }
        }
        expect("completion handles acknowledgement racing the callback") {
            val mapper = mockk<ProcessingJobMapper>()
            every { mapper.transition(1, "SUBMISSION_UNKNOWN", "COMPLETED") } returns 0
            every { mapper.transition(1, "SUBMITTED", "COMPLETED") } returns 1
            ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper)).complete(job)
        }
        expect("acknowledgement and completion reject unrelated lifecycle states") {
            val mapper = mockk<ProcessingJobMapper>()
            every { mapper.transition(any(), any(), any()) } returns 0
            every { mapper.find("key") } returns job.copy(state = "RESETTING")
            shouldThrow<IllegalStateException> {
                ProcessingJobCommand(
                    mapper,
                    ProcessingJobClaimCommand(mapper),
                ).acknowledgeSubmission(job)
            }
            shouldThrow<IllegalStateException> { ProcessingJobCommand(mapper, ProcessingJobClaimCommand(mapper)).complete(job) }
        }
    })
