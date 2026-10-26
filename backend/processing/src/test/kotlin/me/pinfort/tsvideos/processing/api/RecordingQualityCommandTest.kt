package me.pinfort.tsvideos.processing.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.processing.domain.ProcessingAttemptKind
import me.pinfort.tsvideos.processing.domain.ProcessingErrorCode
import me.pinfort.tsvideos.processing.domain.ProcessingIdentity
import me.pinfort.tsvideos.processing.domain.ProcessingJob.State
import me.pinfort.tsvideos.processing.domain.ProcessingOutcome
import me.pinfort.tsvideos.processing.domain.QualityAction
import me.pinfort.tsvideos.processing.domain.QualityReleaseRefusedException
import me.pinfort.tsvideos.processing.domain.QualityReleaseRefusedException.Reason
import me.pinfort.tsvideos.processing.domain.RecordingQualityDecision
import me.pinfort.tsvideos.processing.persistence.ProcessingHistoryMapper
import me.pinfort.tsvideos.processing.persistence.ProcessingJobMapper
import me.pinfort.tsvideos.processing.persistence.RecordingQualityMapper
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.time.Instant

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@MybatisTest
@SpringJUnitConfig
@Import(RecordingQualityCommand::class)
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class RecordingQualityCommandTest : ExpectSpec() {
    @Autowired private lateinit var command: RecordingQualityCommand

    @Autowired private lateinit var jobs: ProcessingJobMapper

    @Autowired private lateinit var histories: ProcessingHistoryMapper

    @Autowired private lateinit var qualityMapper: RecordingQualityMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager

    init {
        expect("quarantine evidence persists and release requires a terminal attempt and unchanged source") {
            val source = Files.createTempFile("quality-", ".m2ts").toFile()
            try {
                source.writeText("original")
                val identity = ProcessingIdentity.from(source)
                jobs.claim(identity.key, identity.path, identity.size, identity.modifiedMillis, State.CLAIMED)
                val job = checkNotNull(jobs.find(identity.key))
                val history = ProcessingHistoryCommand(histories, transactions, mockk(relaxed = true))
                val attempt = history.begin(job, ProcessingAttemptKind.PROCESS)
                command.record(job, attempt.id, RecordingQualityDecision(QualityAction.QUARANTINE, emptyList()))
                jobs.find(identity.key)?.state shouldBe State.QUARANTINED
                // The quarantine finishes its own attempt, so nothing is left ACTIVE to block release.
                checkNotNull(histories.attempt(attempt.id)).let {
                    it.outcome shouldBe ProcessingOutcome.FAILED
                    it.errorCode shouldBe ProcessingErrorCode.QUALITY_QUARANTINED
                }
                val saved = command.reports(job.id).single()
                saved.sourcePath shouldBe source.canonicalPath
                Instant.parse(saved.checkedAt).isAfter(Instant.EPOCH) shouldBe true
                val other = history.begin(job, ProcessingAttemptKind.PROCESS)
                shouldThrow<QualityReleaseRefusedException> { command.release(job.id) }.reason shouldBe Reason.ACTIVE_ATTEMPT
                history.finish(other.id, ProcessingOutcome.FAILED, ProcessingErrorCode.OPERATION_FAILED)
                source.appendText("changed")
                shouldThrow<QualityReleaseRefusedException> { command.release(job.id) }.reason shouldBe Reason.SOURCE_CHANGED
                source.writeText("original")
                source.setLastModified(identity.modifiedMillis) shouldBe true
                command.release(job.id, dryRun = true)
                jobs.find(identity.key)?.state shouldBe State.QUARANTINED
                command.release(job.id)
                jobs.find(identity.key)?.state shouldBe State.RETRYABLE
                command.reports(job.id).single().action shouldBe QualityAction.QUARANTINE
                shouldThrow<QualityReleaseRefusedException> { command.release(job.id) }.reason shouldBe Reason.NOT_QUARANTINED
                jobs.retry(identity.key, State.RETRYABLE, State.CLAIMED) shouldBe 1
                jobs.link(job.id, 987654321, State.CLAIMED) shouldBe 1
                jobs.transition(job.id, State.CLAIMED, State.RETRYABLE) shouldBe 1
                jobs.removeReset(987654321, State.RESETTABLE) shouldBe 1
                RecordingQualityCommand(qualityMapper, histories).reports(job.id).single() shouldBe saved
                history.status(job.id).single().jobState shouldBe "RETIRED"
            } finally {
                source.delete()
            }
        }

        expect("a failed quarantine commits nothing and leaves the attempt ACTIVE for the caller to fail") {
            val source = Files.createTempFile("quality-", ".m2ts").toFile()
            try {
                source.writeText("original")
                val identity = ProcessingIdentity.from(source)
                jobs.claim(identity.key, identity.path, identity.size, identity.modifiedMillis, State.CLAIMED)
                val job = checkNotNull(jobs.find(identity.key))
                val history = ProcessingHistoryCommand(histories, transactions, mockk(relaxed = true))
                val attempt = history.begin(job, ProcessingAttemptKind.PROCESS)
                val failing = mockk<RecordingQualityMapper>()
                every { failing.lockJob(any()) } answers { qualityMapper.lockJob(firstArg()) }
                every { failing.ownedAttempt(any(), any()) } answers { qualityMapper.ownedAttempt(firstArg(), secondArg()) }
                every { failing.insert(any(), any(), any(), any(), any(), any()) } answers {
                    qualityMapper.insert(arg(0), arg(1), arg(2), arg(3), arg(4), arg(5))
                }
                every { failing.transition(any(), any(), any()) } returns 0
                shouldThrow<IllegalStateException> {
                    TransactionTemplate(transactions).executeWithoutResult {
                        RecordingQualityCommand(failing, histories)
                            .record(job, attempt.id, RecordingQualityDecision(QualityAction.QUARANTINE, emptyList()))
                    }
                }.message shouldBe "Job ${job.id} could not be quarantined"
                jobs.find(identity.key)?.state shouldBe State.CLAIMED
                command.reports(job.id) shouldBe emptyList()
                checkNotNull(histories.attempt(attempt.id)).outcome shouldBe ProcessingOutcome.ACTIVE
            } finally {
                source.delete()
            }
        }
    }
}
