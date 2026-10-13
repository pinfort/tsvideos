package me.pinfort.tsvideos.core.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.domain.ProcessingAttemptKind
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.ProcessingOutcome
import me.pinfort.tsvideos.core.domain.QualityAction
import me.pinfort.tsvideos.core.domain.RecordingQualityDecision
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingHistoryMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingJobMapper
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

    @Autowired private lateinit var qualityMapper: me.pinfort.tsvideos.core.external.database.mapper.RecordingQualityMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager

    init {
        expect("quarantine evidence persists and release requires a terminal attempt and unchanged source") {
            val source = Files.createTempFile("quality-", ".m2ts").toFile()
            try {
                source.writeText("original")
                val identity = ProcessingIdentity.from(source)
                jobs.claim(identity.key, identity.path, identity.size, identity.modifiedMillis)
                val job = checkNotNull(jobs.find(identity.key))
                val history = ProcessingHistoryCommand(histories, transactions)
                val attempt = history.begin(job, ProcessingAttemptKind.PROCESS)
                command.record(job, attempt.id, RecordingQualityDecision(QualityAction.QUARANTINE, emptyList()))
                jobs.find(identity.key)?.state shouldBe "QUARANTINED"
                val saved = command.reports(job.id).single()
                saved.sourcePath shouldBe source.canonicalPath
                Instant.parse(saved.checkedAt).isAfter(Instant.EPOCH) shouldBe true
                shouldThrow<IllegalStateException> { command.release(job.id) }
                history.finish(
                    attempt.id,
                    ProcessingOutcome.FAILED,
                    me.pinfort.tsvideos.core.domain.ProcessingErrorCode.QUALITY_QUARANTINED,
                )
                source.appendText("changed")
                shouldThrow<IllegalStateException> { command.release(job.id) }
                source.writeText("original")
                source.setLastModified(identity.modifiedMillis) shouldBe true
                command.release(job.id, dryRun = true)
                jobs.find(identity.key)?.state shouldBe "QUARANTINED"
                command.release(job.id)
                jobs.find(identity.key)?.state shouldBe "RETRYABLE"
                command.reports(job.id).single().action shouldBe QualityAction.QUARANTINE
                shouldThrow<IllegalStateException> { command.release(job.id) }
                jobs.retry(identity.key) shouldBe 1
                jobs.link(job.id, 987654321) shouldBe 1
                jobs.transition(job.id, "CLAIMED", "RETRYABLE") shouldBe 1
                jobs.removeReset(987654321) shouldBe 1
                RecordingQualityCommand(qualityMapper).reports(job.id).single() shouldBe saved
                history.status(job.id).single().jobState shouldBe "RETIRED"
            } finally {
                source.delete()
            }
        }
    }
}
