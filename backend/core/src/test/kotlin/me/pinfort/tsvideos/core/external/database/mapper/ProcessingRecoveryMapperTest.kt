package me.pinfort.tsvideos.core.external.database.mapper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.command.ProcessingRecoveryCommand
import me.pinfort.tsvideos.core.command.VerifiedUploadCommand
import me.pinfort.tsvideos.core.domain.CheckpointEvidence
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.ResumeBlocked
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProcessingRecoveryMapperTest : ExpectSpec() {
    @Autowired private lateinit var source: DataSource

    @Autowired private lateinit var mapper: ProcessingRecoveryMapper

    @Autowired private lateinit var jobs: ProcessingJobMapper

    @Autowired private lateinit var executed: ExecutedFileMapper

    @Autowired private lateinit var splits: SplittedFileMapper

    @Autowired private lateinit var programs: ProgramMapper

    @Autowired private lateinit var tx: PlatformTransactionManager

    private fun sql(s: String) {
        source.connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { it.executeUpdate(s) }
        }
    }

    private fun command(): ProcessingRecoveryCommand {
        val uploads = mockk<VerifiedUploadCommand>()
        every { uploads.inspectRecovery(any(), any()) } returns null
        return ProcessingRecoveryCommand(mapper, jobs, executed, splits, programs, uploads, tx)
    }

    init {
        fun clean() {
            listOf(
                "processing_checkpoint_invalidation",
                "processing_checkpoint",
                "processing_callback_input",
                "processing_stage_event",
                "processing_attempt",
                "processing_history_identity",
                "processing_job",
                "created_file",
                "splitted_file",
                "program",
                "executed_file",
            ).forEach {
                sql("DELETE FROM $it")
            }
        }

        fun seed(file: java.io.File) {
            clean()
            val identity = ProcessingIdentity.from(file)
            val path = identity.path.replace("\\", "/").replace("'", "''")
            sql(
                "INSERT INTO processing_job(id,identity_key,input_path,input_size,modified_millis,state) VALUES(1,'${identity.key}','$path',${identity.size},${identity.modifiedMillis},'CLAIMED')",
            )
            sql(
                "INSERT INTO processing_attempt(id,job_id,attempt_number,kind,outcome,ended_at) VALUES(1,1,1,'PROCESS','FAILED',UTC_TIMESTAMP(6))",
            )
        }
        expect("concurrent resume has exactly one durable owner; stale ownership never expires") {
            val file = Files.createTempFile("resume-input", ".m2ts").toFile()
            file.writeText("recording")
            try {
                seed(file)
                val start = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val winners =
                        (1..2).map {
                            pool.submit<String?> {
                                check(start.await(10, TimeUnit.SECONDS))
                                try {
                                    command().acquire(1, true)
                                } catch (
                                    _: ResumeBlocked,
                                ) {
                                    null
                                }
                            }
                        }
                    start.countDown()
                    val tokens = winners.map { it.get(10, TimeUnit.SECONDS) }.filterNotNull()
                    tokens.size shouldBe 1
                    command().plan(1).blocked shouldBe true
                    jobs.retry(ProcessingIdentity.from(file).key) shouldBe 0
                    jobs.prepareReset(1) shouldBe 0
                    jobs.claimCallback(1, "callback") shouldBe 0
                    command().release(1, "not-owner")
                    mapper.owner(1) shouldBe tokens.single()
                    command().release(1, tokens.single())
                    mapper.owner(1) shouldBe null
                } finally {
                    pool.shutdownNow()
                }
            } finally {
                file.delete()
            }
        }
        expect("fast callback uses an independent token only after submission CAS and completion absorbs acknowledgement") {
            val file = Files.createTempFile("resume-fast", ".m2ts").toFile()
            try {
                seed(file)
                val token = command().acquire(1, true)
                sql("INSERT INTO processing_attempt(job_id,attempt_number,kind,outcome) VALUES(1,2,'RESUME','ACTIVE')")
                jobs.claimCallback(1, "callback") shouldBe 0
                jobs.transition(1, "CLAIMED", "SUBMISSION_UNKNOWN") shouldBe 1
                jobs.claimCallback(1, "callback") shouldBe 1
                mapper.owner(1) shouldBe token
                val jobCommand =
                    me.pinfort.tsvideos.core.command.ProcessingJobCommand(
                        jobs,
                        me.pinfort.tsvideos.core.command
                            .ProcessingJobClaimCommand(jobs),
                    )
                jobCommand.complete(command().job(1))
                jobCommand.acknowledgeSubmission(command().job(1))
                command().job(1).state shouldBe "COMPLETED"
                jobs.releaseCallback(1, "callback") shouldBe 1
                sql("UPDATE processing_attempt SET outcome='COMPLETED' WHERE attempt_number=2")
                command().release(1, token)
                mapper.owner(1) shouldBe null
            } finally {
                file.delete()
            }
        }
        expect("failed attempt start releases only a token without an ACTIVE record") {
            val file = Files.createTempFile("resume-input", ".m2ts").toFile()
            try {
                seed(file)
                val token = command().acquire(1, true)
                sql("INSERT INTO processing_attempt(job_id,attempt_number,kind,outcome) VALUES(1,2,'RESUME','ACTIVE')")
                command().release(1, token)
                mapper.owner(1) shouldBe token
                shouldThrow<ResumeBlocked> { command().acquire(1, true) }
                sql("UPDATE processing_attempt SET outcome='INTERRUPTED',ended_at=UTC_TIMESTAMP(6) WHERE attempt_number=2")
                command().release(1, token)
                mapper.owner(1) shouldBe null
            } finally {
                file.delete()
            }
        }
        expect("immutable successful evidence survives reconstruction and rollback invalidates reuse") {
            val file = Files.createTempFile("resume-input", ".m2ts").toFile()
            file.writeText("recording")
            try {
                seed(file)
                val command = command()
                val token = command.acquire(1, true)
                val evidence = CheckpointEvidence(listOf(command.capture(file)), referenceId = 8)
                sql("INSERT INTO processing_attempt(id,job_id,attempt_number,kind,outcome) VALUES(2,1,2,'RESUME','ACTIVE')")
                command.save(1, 2, token, "SPLIT", evidence)
                command().evidence(1)["SPLIT"] shouldBe evidence
                command.verify(evidence.files.single())
                val modified = file.lastModified()
                file.writeText("changed!!")
                file.setLastModified(modified)
                shouldThrow<IllegalStateException> { command.verify(evidence.files.single()) }
                command.invalidate(1, token)
                command().evidence(1).containsKey("SPLIT") shouldBe false
                sql("UPDATE processing_attempt SET outcome='COMPLETED',ended_at=UTC_TIMESTAMP(6) WHERE id=2")
                command.release(1, token)
            } finally {
                file.delete()
            }
        }
        expect("unknown submission never produces an automatic enqueue plan and dry run writes nothing") {
            val file = Files.createTempFile("resume-input", ".m2ts").toFile()
            try {
                seed(file)
                sql("UPDATE processing_job SET state='SUBMISSION_UNKNOWN' WHERE id=1")
                val plan = command().plan(1)
                plan.blocked shouldBe true
                plan.stages.single().reason shouldBe "RECONCILE_ENCODER_QUEUE_NO_AUTOMATIC_RESUBMISSION"
                mapper.attempts(1) shouldBe 1
                mapper.checkpoints(1).size shouldBe 0
                mapper.owner(1) shouldBe null
                shouldThrow<ResumeBlocked> { command().acquire(1, true) }
            } finally {
                file.delete()
            }
        }
        expect("missing generated artifacts are distinct from changed evidence") {
            val file = Files.createTempFile("resume-input", ".m2ts").toFile()
            val generated = Files.createTempFile("resume-split", ".m2ts").toFile()
            try {
                seed(file)
                val command = command()
                val token = command.acquire(1, true)
                sql("INSERT INTO processing_attempt(id,job_id,attempt_number,kind,outcome) VALUES(2,1,2,'RESUME','ACTIVE')")
                command.save(1, 2, token, "SPLIT", CheckpointEvidence(listOf(command.capture(generated, 1)), 1))
                sql("UPDATE processing_attempt SET outcome='COMPLETED',ended_at=UTC_TIMESTAMP(6) WHERE id=2")
                command.release(1, token)
                generated.delete()
                command
                    .plan(1)
                    .stages
                    .single { it.stage == "SPLIT" }
                    .reason shouldBe "LOCAL_ARTIFACT_MISSING"
            } finally {
                file.delete()
                generated.delete()
            }
        }
    }
}
