package me.pinfort.tsvideos.core.external.database.mapper

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.command.BulkLibraryCommand
import me.pinfort.tsvideos.core.command.CreatedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileTagCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.component.DirectoryNameComponent
import me.pinfort.tsvideos.core.component.NormalizeComponent
import me.pinfort.tsvideos.core.domain.BulkAction
import me.pinfort.tsvideos.core.domain.BulkApplyRequest
import me.pinfort.tsvideos.core.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.core.domain.BulkRequest
import me.pinfort.tsvideos.core.external.samba.NasComponent
import me.pinfort.tsvideos.core.external.samba.SambaClient
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

    @Autowired private lateinit var programMapper: ProgramMapper

    @Autowired private lateinit var executedMapper: ExecutedFileMapper

    @Autowired private lateinit var splitMapper: SplittedFileMapper

    @Autowired private lateinit var createdMapper: CreatedFileMapper

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
        expect("actual command rejects added files, isolates failure and preserves local original on deletion") {
            seed()
            val original =
                java.nio.file.Files
                    .createTempFile("bulk-original", ".m2ts")
                    .toFile()
            original.writeText("original recording")
            try {
                val path = original.path.replace("\\", "/").replace("'", "''")
                sql(
                    "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                        "VALUES(3,'$path',0,18,'2026-01-01','1','title','channel',1,'SPLITTED')",
                )
                val nas = mockk<NasComponent>()
                every { nas.deleteResource(any()) } returns SambaClient.NasType.VIDEO_STORE_NAS
                val logger = mockk<org.slf4j.Logger>(relaxed = true)
                val directories = DirectoryNameComponent(NormalizeComponent())
                val tags = mockk<ExecutedFileTagCommand>(relaxed = true)
                val programCommand =
                    ProgramCommand(
                        programMapper,
                        createdMapper,
                        ExecutedFileCommand(executedMapper, tags, logger),
                        CreatedFileCommand(createdMapper, mockk(), nas, logger),
                        splitMapper,
                        logger,
                        SplittedFileCommand(splitMapper, logger),
                        directories,
                        tags,
                    )
                val command =
                    BulkLibraryCommand(
                        programCommand,
                        executedMapper,
                        splitMapper,
                        createdMapper,
                        mapper,
                        directories,
                        NormalizeComponent(),
                        transactionManager,
                        logger,
                    )
                val request = BulkRequest(listOf(4), BulkAction.DELETE)
                val initial = command.preview(request)
                sql("INSERT INTO created_file(id,splitted_file_id,file,size,status) VALUES(6,2,'base/a/alpha/second.mp4',2,'FILE_MOVED')")
                command
                    .apply(
                        BulkApplyRequest(request, initial.targets.associate { it.id to it.fingerprint }),
                    ).outcomes
                    .single()
                    .status shouldBe
                    BulkOutcomeStatus.STALE
                val plan = command.preview(request)
                every { nas.deleteResource("base/a/alpha/second.mp4") } throws IllegalStateException("unavailable")
                command
                    .apply(
                        BulkApplyRequest(request, plan.targets.associate { it.id to it.fingerprint }),
                    ).outcomes
                    .single()
                    .status shouldBe
                    BulkOutcomeStatus.FAILED
                mapper.allFiles().size shouldBe 2
                programMapper.find(4)?.id shouldBe 4
                original.readText() shouldBe "original recording"
                every { nas.deleteResource(any()) } returns SambaClient.NasType.VIDEO_STORE_NAS
                command
                    .apply(
                        BulkApplyRequest(request, plan.targets.associate { it.id to it.fingerprint }),
                    ).outcomes
                    .single()
                    .status shouldBe
                    BulkOutcomeStatus.SUCCESS
                programMapper.find(4) shouldBe null
                mapper.allFiles().size shouldBe 0
                original.readText() shouldBe "original recording"
            } finally {
                original.delete()
            }
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
