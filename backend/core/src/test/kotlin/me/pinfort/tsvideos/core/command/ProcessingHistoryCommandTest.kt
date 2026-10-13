package me.pinfort.tsvideos.core.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.domain.ProcessingArtifact
import me.pinfort.tsvideos.core.domain.ProcessingAttemptKind
import me.pinfort.tsvideos.core.domain.ProcessingErrorCode
import me.pinfort.tsvideos.core.domain.ProcessingOutcome
import me.pinfort.tsvideos.core.domain.ProcessingStage
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingHistoryMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingJobMapper
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class ProcessingHistoryCommandTest : ExpectSpec() {
    @Autowired private lateinit var mapper: ProcessingHistoryMapper

    @Autowired private lateinit var jobs: ProcessingJobMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Autowired private lateinit var dataSource: DataSource

    private fun command() = ProcessingHistoryCommand(mapper, transactions)

    private fun job() =
        UUID.randomUUID().toString().let { key ->
            jobs.claim(key, "/recordings/$key.m2ts", 3, 100)
            checkNotNull(jobs.find(key))
        }

    init {
        expect("owned interruption persists across command restart and leaves other attempts active") {
            val cancellation =
                me.pinfort.tsvideos.core.component
                    .ProcessingCancellation()
            val command = ProcessingHistoryCommand(mapper, transactions, cancellation)
            val job = job()
            val owned = command.begin(job, ProcessingAttemptKind.PROCESS)
            val other = command.begin(job, ProcessingAttemptKind.CALLBACK)
            val entered = CountDownLatch(1)
            val worker =
                Thread {
                    try {
                        cancellation.runOwned {
                            command.stage(owned.id, ProcessingStage.SPLIT) {
                                entered.countDown()
                                Thread.sleep(60000)
                            }
                        }
                    } catch (failure: InterruptedException) {
                        command.failed(owned.id, failure)
                    }
                }
            worker.start()
            entered.await(10, TimeUnit.SECONDS) shouldBe true
            cancellation.requestStop()
            worker.join(5000)
            worker.isAlive shouldBe false
            val persisted = command().history(job.id)
            persisted.first { it.attempt.id == owned.id }.attempt.outcome shouldBe ProcessingOutcome.INTERRUPTED
            persisted
                .first { it.attempt.id == owned.id }
                .stages
                .single()
                .outcome shouldBe ProcessingOutcome.INTERRUPTED
            persisted.first { it.attempt.id == other.id }.attempt.outcome shouldBe ProcessingOutcome.ACTIVE
        }
        expect("attempt numbers are monotonic across concurrent starts and new command instances") {
            val job = job()
            val pool = Executors.newFixedThreadPool(6)
            val gate = CountDownLatch(1)
            try {
                val futures =
                    (1..6).map {
                        pool.submit(
                            Callable {
                                gate.await()
                                command().begin(job, ProcessingAttemptKind.PROCESS)
                            },
                        )
                    }
                gate.countDown()
                val attempts = futures.map { it.get(30, TimeUnit.SECONDS) }
                attempts.map { it.attemptNumber }.sorted() shouldBe (1..6).toList()
                command().begin(job, ProcessingAttemptKind.CALLBACK).attemptNumber shouldBe 7
                command().history(job.id).size shouldBe 7
                command()
                    .status(job.id)
                    .single()
                    .activeAttempts.size shouldBe 7
            } finally {
                pool.shutdownNow()
            }
        }
        expect("stage completion stores UTC times and artifact references without holding transaction across work") {
            val command = command()
            val job = job()
            val attempt = command.begin(job, ProcessingAttemptKind.PROCESS)
            val before = Instant.now().minusSeconds(5)
            command.stage(attempt.id, ProcessingStage.UPLOAD_ORIGINAL, { ProcessingArtifact("UPLOAD_MANIFEST", it, "base/artifact.gz") }) {
                TransactionSynchronizationManager.isActualTransactionActive() shouldBe false
                123L
            } shouldBe 123L
            command.finish(attempt.id, ProcessingOutcome.COMPLETED)
            val history = command().history(job.id).single()
            history.stages.single().artifactId shouldBe 123L
            history.stages.single().artifactPath shouldBe "base/artifact.gz"
            history.attempt.outcome shouldBe ProcessingOutcome.COMPLETED
            Instant.parse(history.attempt.startedAt).isAfter(before) shouldBe true
            Instant.parse(history.attempt.endedAt).isBefore(Instant.now().plusSeconds(5)) shouldBe true
        }
        expect("secrets in exception causes never enter retained failures") {
            val command = command()
            val job = job()
            val attempt = command.begin(job, ProcessingAttemptKind.PROCESS)
            val failure =
                shouldThrow<java.io.IOException> {
                    command.stage(
                        attempt.id,
                        ProcessingStage.COMPRESS,
                    ) { throw java.io.IOException("password=supersecret smb://user:supersecret@nas") }
                }
            command.failed(attempt.id, failure)
            val history = command().history(job.id).single()
            history.attempt.errorCode shouldBe ProcessingErrorCode.IO_FAILURE
            history.stages.single().errorCode shouldBe ProcessingErrorCode.IO_FAILURE
            history.toString().contains("supersecret") shouldBe false
        }
        expect("interruptions close the stage and attempt distinctly") {
            val command = command()
            val job = job()
            val attempt = command.begin(job, ProcessingAttemptKind.CALLBACK)
            val failure =
                shouldThrow<InterruptedException> {
                    command.stage(attempt.id, ProcessingStage.FINALIZE) { throw InterruptedException("secret message") }
                }
            command.failed(attempt.id, failure)
            command
                .history(job.id)
                .single()
                .attempt.outcome shouldBe ProcessingOutcome.INTERRUPTED
            command
                .history(job.id)
                .single()
                .stages
                .single()
                .outcome shouldBe ProcessingOutcome.INTERRUPTED
        }
        expect("heartbeats update activity without changing recording identity or inferring owner death") {
            val command = command()
            val job = job()
            val attempt = command.begin(job, ProcessingAttemptKind.PROCESS)
            command.heartbeat(attempt.id)
            val current = command.history(job.id).single()
            current.identity.modifiedMillis shouldBe 100
            (Instant.parse(current.attempt.activityAt) >= Instant.parse(attempt.activityAt)) shouldBe true
            current.attempt.outcome shouldBe ProcessingOutcome.ACTIVE
            current.attempt.endedAt shouldBe null
        }
        expect("terminal attempts and duplicate stage finishes reject stale mutation") {
            val command = command()
            val attempt = command.begin(job(), ProcessingAttemptKind.PROCESS)
            val event = command.startStage(attempt.id, ProcessingStage.INSPECT)
            shouldThrow<IllegalStateException> { command.startStage(attempt.id, ProcessingStage.SPLIT) }
            shouldThrow<IllegalStateException> { command.finish(attempt.id, ProcessingOutcome.COMPLETED) }
            command.finishStage(event, ProcessingOutcome.COMPLETED)
            shouldThrow<IllegalStateException> { command.finishStage(event, ProcessingOutcome.COMPLETED) }
            command.finish(attempt.id, ProcessingOutcome.COMPLETED)
            shouldThrow<IllegalStateException> { command.startStage(attempt.id, ProcessingStage.SPLIT) }
            shouldThrow<IllegalStateException> { command.heartbeat(attempt.id) }
        }
        expect("history survives live job reset and contains immutable input identity") {
            val command = command()
            val job = job()
            val attempt = command.begin(job, ProcessingAttemptKind.PROCESS)
            command.finish(attempt.id, ProcessingOutcome.FAILED, ProcessingErrorCode.OPERATION_FAILED)
            jobs.transition(job.id, "CLAIMED", "RETRYABLE") shouldBe 1
            jobs.link(job.id, 999999) shouldBe 0
            dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM processing_job WHERE id=?").use { statement ->
                    statement.setLong(1, job.id)
                    statement.executeUpdate()
                }
                connection.commit()
            }
            command().status(job.id).single().jobState shouldBe "RETIRED"
            command()
                .history(job.id)
                .single()
                .identity.inputPath shouldBe job.inputPath
        }
        expect("documented cleanup removes old terminal attempts but retains active evidence and numbering") {
            val command = command()
            val job = job()
            val old = command.begin(job, ProcessingAttemptKind.PROCESS)
            command.stage(old.id, ProcessingStage.INSPECT) { Unit }
            command.finish(old.id, ProcessingOutcome.COMPLETED)
            val active = command.begin(job, ProcessingAttemptKind.PROCESS)
            command.startStage(active.id, ProcessingStage.SPLIT)
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "UPDATE processing_attempt SET started_at='2000-01-01',ended_at=IF(outcome='ACTIVE',NULL,'2000-01-01'),activity_at='2000-01-01' WHERE job_id=${job.id}",
                    )
                    connection.commit()
                    val script = checkNotNull(javaClass.getResource("/db/maintenance/processing_history_cleanup.sql")).readText()
                    script
                        .lineSequence()
                        .filterNot {
                            it.startsWith("--")
                        }.joinToString("\n")
                        .split(';')
                        .filter { it.isNotBlank() }
                        .forEach { statement.execute(it) }
                }
            }
            command.history(job.id).map { it.attempt.id } shouldBe listOf(active.id)
            command
                .history(job.id)
                .single()
                .stages
                .single()
                .outcome shouldBe ProcessingOutcome.ACTIVE
            command.begin(job, ProcessingAttemptKind.CALLBACK).attemptNumber shouldBe 3
        }
    }
}
