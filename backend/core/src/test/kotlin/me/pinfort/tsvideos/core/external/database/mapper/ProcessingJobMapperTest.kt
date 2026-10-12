package me.pinfort.tsvideos.core.external.database.mapper

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED,
)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class ProcessingJobMapperTest : ExpectSpec() {
    @Autowired private lateinit var mapper: ProcessingJobMapper

    @Autowired private lateinit var dataSource: javax.sql.DataSource

    init {
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
            mapper.claimCallback(job.id, "legacy-owner") shouldBe 1
            mapper.state(job.id, "COMPLETED") shouldBe 1
            mapper.releaseCallback(job.id, "legacy-owner") shouldBe 1
            mapper.prepareReset(job.id) shouldBe 1
            mapper.claimCallback(job.id, "racing-callback") shouldBe 0
        }
        expect("only proven rollback can be retried and active claims cannot reset") {
            val key = UUID.randomUUID().toString()
            mapper.claim(key, "/safe-retry", 1, 2) shouldBe 1
            val job = checkNotNull(mapper.find(key))
            mapper.prepareReset(job.id) shouldBe 0
            mapper.retry(key) shouldBe 0
            mapper.state(job.id, "RETRYABLE") shouldBe 1
            mapper.retry(key) shouldBe 1
            mapper.retry(key) shouldBe 0
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
                                mapper.claim(key, "/recording", 12, 13)
                            },
                        )
                    }
                start.countDown()
                futures.sumOf { it.get(20, TimeUnit.SECONDS) } shouldBe 1
                mapper.claim(key, "/recording", 12, 13) shouldBe 0
                val job = checkNotNull(mapper.find(key))
                job.inputSize shouldBe 12
                job.state shouldBe "CLAIMED"
                mapper.link(job.id, 54321) shouldBe 1
                mapper.findByExecutedFile(54321)?.id shouldBe job.id
            } finally {
                pool.shutdownNow()
            }
        }
        expect("callback ownership is exclusive and manifest cannot change on retry") {
            val key = UUID.randomUUID().toString()
            mapper.claim(key, "/callback", 1, 2)
            val job = checkNotNull(mapper.find(key))
            mapper.state(job.id, "SUBMITTED")
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val futures =
                    (1..4).map { n ->
                        pool.submit(
                            Callable {
                                start.await()
                                mapper.claimCallback(job.id, "owner-$n")
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
                mapper.claimCallback(job.id, "retry") shouldBe 1
                mapper.manifest(job.id, "retry", "manifest-one") shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }
        expect("uncertain submission requires explicit reconciliation and cannot be reset") {
            val key = UUID.randomUUID().toString()
            mapper.claim(key, "/unknown", 1, 2)
            val job = checkNotNull(mapper.find(key))
            mapper.link(job.id, 65432)
            mapper.state(job.id, "SUBMISSION_UNKNOWN") shouldBe 1
            mapper.removeReset(65432) shouldBe 0
            mapper.reconcile(job.id, "SUBMISSION_UNKNOWN", "REJECTED") shouldBe 1
            mapper.removeReset(65432) shouldBe 1
        }
    }
}
