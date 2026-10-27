package me.pinfort.tsvideos.processing.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import me.pinfort.tsvideos.processing.api.ProcessingJobClaimCommand
import me.pinfort.tsvideos.processing.api.ProcessingJobCommand
import me.pinfort.tsvideos.processing.domain.ProcessingIdentity
import me.pinfort.tsvideos.processing.domain.ProcessingJob.State
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.context.annotation.Import
import org.springframework.dao.DuplicateKeyException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

private const val MIGRATION_075 = "075_processing_job_executed_file_unique.sql"
private const val UNIQUE_INDEX = "executed_file_id_unique"

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ProcessingJobCommand::class, ProcessingJobClaimCommand::class)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class ProcessingJobMapperTest : ExpectSpec() {
    @Autowired private lateinit var mapper: ProcessingJobMapper

    @Autowired private lateinit var command: ProcessingJobCommand

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private fun claim(
        key: String,
        path: String,
    ) = mapper.claim(key, path, 1, 2, State.CLAIMED)

    private fun storedState(id: Long): String =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT state FROM processing_job WHERE id=?").use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getString(1)
                }
            }
        }

    /** Puts a job directly into [state], bypassing the guarded operations under test. */
    private fun forceState(
        id: Long,
        state: State,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE processing_job SET state=? WHERE id=?").use { statement ->
                statement.setString(1, state.name)
                statement.setLong(2, id)
                check(statement.executeUpdate() == 1)
            }
            if (!connection.autoCommit) connection.commit()
        }
    }

    private fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
            if (!connection.autoCommit) connection.commit()
        }
    }

    private fun count(sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
        }

    init {
        expect("every state is persisted and read back by its exact constant name") {
            val key = UUID.randomUUID().toString()
            claim(key, "/persisted-names") shouldBe 1
            val job = checkNotNull(mapper.find(key))
            storedState(job.id) shouldBe "CLAIMED"
            State.entries.zipWithNext().forEach { (from, to) ->
                mapper.transition(job.id, from, to) shouldBe 1
                storedState(job.id) shouldBe to.name
                mapper.find(key)?.state shouldBe to
            }
        }
        expect("stale transitions and repeated registration links cannot overwrite a job") {
            val key = UUID.randomUUID().toString()
            claim(key, "/guarded")
            val job = checkNotNull(mapper.find(key))
            mapper.link(job.id, 87654, State.CLAIMED) shouldBe 1
            mapper.link(job.id, 87655, State.CLAIMED) shouldBe 0
            mapper.transition(job.id, State.SUBMITTED, State.COMPLETED) shouldBe 0
            mapper.transition(job.id, State.CLAIMED, State.SUBMISSION_UNKNOWN) shouldBe 1
            mapper.link(job.id, 87656, State.CLAIMED) shouldBe 0
            mapper.transition(job.id, State.CLAIMED, State.RETRYABLE) shouldBe 0
            command.complete(job)
            command.acknowledgeSubmission(job)
            mapper.find(key)?.state shouldBe State.COMPLETED
        }
        expect("duplicate claim can atomically reacquire only a known retryable job") {
            val key = UUID.randomUUID().toString()
            val identity =
                ProcessingIdentity(key, "/retry-command", 1, 2)
            val job = checkNotNull(command.claim(identity))
            command.claim(identity) shouldBe null
            command.transition(job, State.CLAIMED, State.RETRYABLE)
            command.claim(identity)?.id shouldBe job.id
            command.claim(identity) shouldBe null
        }
        expect("a duplicate claim does not mark the caller's transaction rollback-only") {
            val identity = ProcessingIdentity(UUID.randomUUID().toString(), "/outer-duplicate", 1, 2)
            checkNotNull(command.claim(identity))
            val outerKey = UUID.randomUUID().toString()
            TransactionTemplate(transactionManager).executeWithoutResult {
                command.claim(identity) shouldBe null
                claim(outerKey, "/outer-work") shouldBe 1
            }
            mapper.find(outerKey) shouldNotBe null
        }
        expect("documented legacy adoption creates exactly one callback-capable job") {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) VALUES(76543,'legacy-callback',0,2,NOW(),'test','test','test',1,'SPLITTED')",
                    )
                    val adoption =
                        """
                        INSERT INTO processing_job(identity_key,input_path,input_size,modified_millis,state,executed_file_id)
                        SELECT SHA2(CONCAT('legacy:',e.id),256),e.file,e.size,-1,'SUBMITTED',e.id
                        FROM executed_file e WHERE e.id=76543
                        AND NOT EXISTS (SELECT 1 FROM processing_job j WHERE j.executed_file_id=e.id)
                        """.trimIndent()
                    statement.executeUpdate(adoption) shouldBe 1
                    statement.executeUpdate(adoption) shouldBe 0
                    connection.commit()
                }
            }
            val job = checkNotNull(mapper.findByExecutedFile(76543))
            job.modifiedMillis shouldBe -1
            job.state shouldBe State.SUBMITTED
            mapper.claimCallback(job.id, "legacy-owner", State.CALLBACK) shouldBe 1
            mapper.transition(job.id, State.SUBMITTED, State.COMPLETED) shouldBe 1
            mapper.releaseCallback(job.id, "legacy-owner") shouldBe 1
            mapper.prepareReset(job.id, State.RESETTING, State.RESETTABLE) shouldBe 1
            // an interrupted reset can be replayed
            mapper.prepareReset(job.id, State.RESETTING, State.RESETTABLE) shouldBe 1
            mapper.claimCallback(job.id, "racing-callback", State.CALLBACK) shouldBe 0
            mapper.find(job.identityKey)?.updatedAt shouldNotBe null
        }
        expect("only proven rollback can be retried and active claims cannot reset") {
            val key = UUID.randomUUID().toString()
            claim(key, "/safe-retry") shouldBe 1
            val job = checkNotNull(mapper.find(key))
            mapper.prepareReset(job.id, State.RESETTING, State.RESETTABLE) shouldBe 0
            mapper.retry(key, State.RETRYABLE, State.CLAIMED) shouldBe 0
            mapper.transition(job.id, State.CLAIMED, State.RETRYABLE) shouldBe 1
            mapper.retry(key, State.RETRYABLE, State.CLAIMED) shouldBe 1
            mapper.retry(key, State.RETRYABLE, State.CLAIMED) shouldBe 0
        }
        expect("retry starts from a clean link and callback manifest") {
            val key = UUID.randomUUID().toString()
            claim(key, "/stale-manifest") shouldBe 1
            val job = checkNotNull(mapper.find(key))
            mapper.link(job.id, 87700, State.CLAIMED) shouldBe 1
            mapper.transition(job.id, State.CLAIMED, State.SUBMISSION_UNKNOWN) shouldBe 1
            mapper.claimCallback(job.id, "owner", State.CALLBACK) shouldBe 1
            mapper.manifest(job.id, "owner", "a".repeat(64)) shouldBe 1
            mapper.releaseCallback(job.id, "owner") shouldBe 1
            mapper.transition(job.id, State.SUBMISSION_UNKNOWN, State.RETRYABLE) shouldBe 1
            mapper.retry(key, State.RETRYABLE, State.CLAIMED) shouldBe 1
            val retried = checkNotNull(mapper.find(key))
            retried.state shouldBe State.CLAIMED
            retried.executedFileId shouldBe null
            retried.callbackManifest shouldBe null
        }
        expect("one concurrent process owns the durable claim and repeats do not insert") {
            val key = UUID.randomUUID().toString()
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val futures =
                    (1..4).map {
                        pool.submit(
                            Callable {
                                start.await()
                                if (command.claim(
                                        ProcessingIdentity(key, "/recording", 12, 13),
                                    ) !=
                                    null
                                ) {
                                    1
                                } else {
                                    0
                                }
                            },
                        )
                    }
                start.countDown()
                futures.sumOf { it.get(20, TimeUnit.SECONDS) } shouldBe 1
                command.claim(
                    ProcessingIdentity(key, "/recording", 12, 13),
                ) shouldBe null
                val job = checkNotNull(mapper.find(key))
                job.inputSize shouldBe 12
                job.state shouldBe State.CLAIMED
                mapper.link(job.id, 54321, State.CLAIMED) shouldBe 1
                mapper.findByExecutedFile(54321)?.id shouldBe job.id
                command.transition(job, State.CLAIMED, State.RETRYABLE)
                val retryStart = CountDownLatch(1)
                val retries =
                    (1..4).map {
                        pool.submit(
                            Callable {
                                retryStart.await()
                                if (command.claim(
                                        ProcessingIdentity(key, "/recording", 12, 13),
                                    ) != null
                                ) {
                                    1
                                } else {
                                    0
                                }
                            },
                        )
                    }
                retryStart.countDown()
                retries.sumOf { it.get(20, TimeUnit.SECONDS) } shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }
        expect("callback ownership is exclusive and manifest cannot change on retry") {
            val key = UUID.randomUUID().toString()
            claim(key, "/callback")
            val job = checkNotNull(mapper.find(key))
            mapper.transition(job.id, State.CLAIMED, State.SUBMISSION_UNKNOWN)
            mapper.transition(job.id, State.SUBMISSION_UNKNOWN, State.SUBMITTED)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val futures =
                    (1..4).map { n ->
                        pool.submit(
                            Callable {
                                start.await()
                                mapper.claimCallback(job.id, "owner-$n", State.CALLBACK)
                            },
                        )
                    }
                start.countDown()
                futures.sumOf { it.get(20, TimeUnit.SECONDS) } shouldBe 1
                val owner = checkNotNull(mapper.find(key)?.callbackClaim)
                mapper.manifest(job.id, owner, "manifest-one") shouldBe 1
                mapper.manifest(job.id, owner, "manifest-two") shouldBe 0
                mapper.releaseCallback(job.id, "wrong-owner") shouldBe 0
                mapper.releaseCallback(job.id, owner) shouldBe 1
                mapper.claimCallback(job.id, "retry", State.CALLBACK) shouldBe 1
                mapper.manifest(job.id, "retry", "manifest-one") shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }
        expect("callbacks cannot claim a job outside the callback states") {
            val key = UUID.randomUUID().toString()
            claim(key, "/callback-states")
            val job = checkNotNull(mapper.find(key))
            mapper.claimCallback(job.id, "early", State.CALLBACK) shouldBe 0
            mapper.transition(job.id, State.CLAIMED, State.RETRYABLE) shouldBe 1
            mapper.claimCallback(job.id, "late", State.CALLBACK) shouldBe 0
            mapper.find(key)?.callbackClaim shouldBe null
        }
        expect("uncertain submission requires explicit reconciliation and cannot be reset") {
            val key = UUID.randomUUID().toString()
            claim(key, "/unknown")
            val job = checkNotNull(mapper.find(key))
            mapper.link(job.id, 65432, State.CLAIMED)
            mapper.transition(job.id, State.CLAIMED, State.SUBMISSION_UNKNOWN) shouldBe 1
            mapper.removeReset(65432, State.RESETTABLE) shouldBe 0
            mapper.reconcile(job.id, State.REJECTED) shouldBe 1
            storedState(job.id) shouldBe "REJECTED"
            mapper.reconcile(job.id, State.SUBMITTED) shouldBe 0
            mapper.removeReset(65432, State.RESETTABLE) shouldBe 1
        }
        expect("reconciliation rejects every state other than SUBMISSION_UNKNOWN and leaves the job unchanged") {
            State.entries.filter { it != State.SUBMISSION_UNKNOWN }.forEach { state ->
                val key = UUID.randomUUID().toString()
                claim(key, "/reconcile-${state.name}") shouldBe 1
                val job = checkNotNull(mapper.find(key))
                forceState(job.id, state)
                val before = checkNotNull(mapper.find(key))
                mapper.reconcile(job.id, State.SUBMITTED) shouldBe 0
                mapper.reconcile(job.id, State.REJECTED) shouldBe 0
                mapper.find(key) shouldBe before
            }
        }
        expect("an executed file links to at most one job while any number of jobs stay unlinked") {
            val first = UUID.randomUUID().toString()
            val second = UUID.randomUUID().toString()
            val third = UUID.randomUUID().toString()
            claim(first, "/unique-first") shouldBe 1
            claim(second, "/unique-second") shouldBe 1
            claim(third, "/unique-third") shouldBe 1
            // Unlinked jobs (NULL executed_file_id) never conflict with each other.
            listOf(first, second, third).map { mapper.find(it)?.executedFileId } shouldBe listOf(null, null, null)
            val owner = checkNotNull(mapper.find(first))
            val other = checkNotNull(mapper.find(second))
            mapper.link(owner.id, 88100, State.CLAIMED) shouldBe 1
            shouldThrow<DuplicateKeyException> { mapper.link(other.id, 88100, State.CLAIMED) }
            val conflict = shouldThrow<IllegalStateException> { command.link(other, 88100) }
            conflict.message shouldContain "Executed file 88100 is already linked to another job"
            conflict.cause.shouldBeInstanceOf<DuplicateKeyException>()
            // The losing job keeps no link and the owner is the only job found for the executed file.
            mapper.find(second)?.executedFileId shouldBe null
            mapper.findByExecutedFile(88100)?.id shouldBe owner.id
            command.link(other, 88101)
            mapper.findByExecutedFile(88101)?.id shouldBe other.id
        }
        expect("documented legacy adoption cannot add a second job to an executed file even without its NOT EXISTS guard") {
            val key = UUID.randomUUID().toString()
            claim(key, "/adoption-conflict") shouldBe 1
            mapper.link(checkNotNull(mapper.find(key)).id, 88200, State.CLAIMED) shouldBe 1
            shouldThrow<SQLException> {
                execute(
                    "INSERT INTO processing_job(identity_key,input_path,input_size,modified_millis,state,executed_file_id) " +
                        "VALUES (SHA2('legacy:88200',256),'legacy-conflict',1,-1,'SUBMITTED',88200)",
                )
            }.message shouldContain "Duplicate entry"
            mapper.findByExecutedFile(88200)?.identityKey shouldBe key
        }
        expect("migration 075 refuses duplicate links without deleting rows and applies once they are reconciled") {
            // A scratch copy of processing_job, so that the shared table never holds the duplicates.
            val table = "processing_job_075_check"

            fun migration(name: String) =
                checkNotNull(ProcessingJobMapperTest::class.java.classLoader.getResource("db/migrations/$name"))
                    .readText()
                    .replace("processing_job", table)
                    .trim()
                    .removeSuffix(";")

            fun indexes(name: String) =
                count(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                        "WHERE table_schema=DATABASE() AND table_name='$table' AND index_name='$name' " +
                        "AND non_unique=${if (name == UNIQUE_INDEX) 0 else 1}",
                )
            execute("DROP TABLE IF EXISTS $table")
            execute(migration("064_processing_job.sql"))
            val insert = "INSERT INTO $table(identity_key,input_path,input_size,modified_millis,state,executed_file_id) VALUES "
            execute(
                insert +
                    "('a','/a',1,1,'SUBMITTED',7), ('b','/b',1,1,'RETRYABLE',7), " +
                    "('c','/c',1,1,'CLAIMED',NULL), ('d','/d',1,1,'CLAIMED',NULL)",
            )
            // The preflight query documented in processing-idempotency.md.
            val preflight =
                "SELECT COUNT(*) FROM (SELECT executed_file_id FROM $table WHERE executed_file_id IS NOT NULL " +
                    "GROUP BY executed_file_id HAVING COUNT(*) > 1) duplicates"
            count(preflight) shouldBe 1
            shouldThrow<SQLException> { execute(migration(MIGRATION_075)) }.message shouldContain "Duplicate entry"
            // The failed ALTER is atomic: every row and the original index survive.
            count("SELECT COUNT(*) FROM $table") shouldBe 4
            indexes("executed_file_id") shouldBe 1
            indexes(UNIQUE_INDEX) shouldBe 0
            // The documented remediation for a RETRYABLE duplicate: clear only its link, as retry() would.
            execute(
                "UPDATE $table SET executed_file_id=NULL " +
                    "WHERE identity_key='b' AND executed_file_id=7 AND state='RETRYABLE' AND callback_claim IS NULL",
            )
            count(preflight) shouldBe 0
            execute(migration(MIGRATION_075))
            // Rerunning is a no-op.
            execute(migration(MIGRATION_075))
            indexes("executed_file_id") shouldBe 0
            indexes(UNIQUE_INDEX) shouldBe 1
            count("SELECT COUNT(*) FROM $table") shouldBe 4
            // Multiple NULLs stay allowed, a second link is rejected.
            execute(insert + "('e','/e',1,1,'CLAIMED',NULL)")
            count("SELECT COUNT(*) FROM $table WHERE executed_file_id IS NULL") shouldBe 4
            shouldThrow<SQLException> { execute(insert + "('f','/f',1,1,'CLAIMED',7)") }.message shouldContain "Duplicate entry"
            execute("DROP TABLE $table")
        }
        expect("reconciliation rejects a callback-claimed SUBMISSION_UNKNOWN job until the claim is released") {
            val key = UUID.randomUUID().toString()
            claim(key, "/reconcile-claimed") shouldBe 1
            val job = checkNotNull(mapper.find(key))
            mapper.transition(job.id, State.CLAIMED, State.SUBMISSION_UNKNOWN) shouldBe 1
            mapper.claimCallback(job.id, "owner", State.CALLBACK) shouldBe 1
            val before = checkNotNull(mapper.find(key))
            mapper.reconcile(job.id, State.SUBMITTED) shouldBe 0
            mapper.find(key) shouldBe before
            mapper.releaseCallback(job.id, "owner") shouldBe 1
            mapper.reconcile(job.id, State.SUBMITTED) shouldBe 1
            val reconciled = checkNotNull(mapper.find(key))
            reconciled.state shouldBe State.SUBMITTED
            reconciled.callbackClaim shouldBe null
        }
    }
}
