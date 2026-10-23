package me.pinfort.tsvideos.catalog.persistence

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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

// The bulk apply over these locks (library's BulkLibraryCommand) is tested in library's BulkLibraryApplyTest.
@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BulkLibraryMapperTest : ExpectSpec() {
    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var mapper: BulkLibraryMapper

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private fun sql(statement: String) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.executeUpdate(statement) }
        }
    }

    init {
        fun seed() {
            listOf("created_file", "splitted_file", "program", "executed_file").forEach { sql("DELETE FROM $it") }
            sql("INSERT INTO created_file(id,splitted_file_id,file,size,status) VALUES(1,2,'base/a/alpha/file.mp4',3,'FILE_MOVED')")
            sql(
                "INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(2,3,'split',3,1,'ENCODE_TASK_ADDED')",
            )
            sql("INSERT INTO program(id,name,executed_file_id,status) VALUES(4,'program',3,'COMPLETED')")
        }
        expect("all files includes orphan references and normalized aliases for shared-path checks") {
            seed()
            sql("INSERT INTO created_file(id,splitted_file_id,file,size,status) VALUES(5,99,'BASE/A/ALPHA/FILE.MP4',3,'FILE_MOVED')")
            mapper.allFiles().map { it.id } shouldBe listOf(1L, 5L)
            mapper.references(3) shouldBe listOf(4L)
        }
        expect("created files of programs follow each program's executed record and skip unknown ids") {
            seed()
            sql("INSERT INTO program(id,name,executed_file_id,status) VALUES(7,'shared',3,'COMPLETED')")
            mapper.createdFilesOfPrograms(listOf(4, 7, 404)).map { it.id } shouldBe listOf(1L, 1L)
            mapper.lockFiles().map { it.id } shouldBe listOf(1L)
        }
        expect("a second bulk lock gives up after the bounded wait with a locking failure") {
            seed()
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val holding =
                    executor.submit {
                        TransactionTemplate(transactionManager).execute {
                            mapper.lockFiles()
                            locked.countDown()
                            check(release.await(30, TimeUnit.SECONDS))
                        }
                    }
                check(locked.await(10, TimeUnit.SECONDS))
                io.kotest.assertions.throwables.shouldThrow<org.springframework.dao.PessimisticLockingFailureException> {
                    TransactionTemplate(transactionManager).execute { mapper.lockFiles() }
                }
                release.countDown()
                holding.get(10, TimeUnit.SECONDS)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
        expect("apply locks prevent concurrent aliases or split additions until transaction completes") {
            seed()
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val insertStarted = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val holding =
                    executor.submit {
                        TransactionTemplate(transactionManager).execute {
                            mapper.lockFiles().size shouldBe 1
                            mapper.lockProgram(4) shouldBe 4
                            mapper.lockSplits(3) shouldBe listOf(2L)
                            mapper.lockReferences(3) shouldBe listOf(4L)
                            locked.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    }
                check(locked.await(10, TimeUnit.SECONDS))
                val insertion =
                    executor.submit {
                        insertStarted.countDown()
                        sql(
                            "INSERT INTO created_file(splitted_file_id,file,size,status) VALUES(99,'BASE/A/ALPHA/FILE.MP4',3,'FILE_MOVED')",
                        )
                        sql(
                            "INSERT INTO splitted_file(executed_file_id,file,size,duration,status) VALUES(3,'new-split',3,1,'REGISTERED')",
                        )
                    }
                check(insertStarted.await(10, TimeUnit.SECONDS))
                io.kotest.assertions.throwables.shouldThrow<java.util.concurrent.TimeoutException> {
                    insertion.get(
                        200,
                        TimeUnit.MILLISECONDS,
                    )
                }
                release.countDown()
                holding.get(10, TimeUnit.SECONDS)
                insertion.get(10, TimeUnit.SECONDS)
                mapper.allFiles().size shouldBe 2
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }
}
