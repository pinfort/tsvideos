package me.pinfort.tsvideos.core.external.database.mapper

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.command.DuplicateProgramCommand
import me.pinfort.tsvideos.core.command.LibraryStatsCommand
import me.pinfort.tsvideos.core.command.StorageOverviewCommand
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class StorageOverviewMapperTest : ExpectSpec() {
    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var mapper: StorageOverviewMapper

    @Autowired private lateinit var stats: LibraryStatsMapper

    @Autowired private lateinit var programs: ProgramMapper

    private fun command() = StorageOverviewCommand(LibraryStatsCommand(stats), DuplicateProgramCommand(programs), mapper)

    init {
        beforeTest { sql(listOf("created_file", "splitted_file", "program", "executed_file").map { "DELETE FROM $it" }) }
        expect("empty overview leaves physical values unknown") {
            val report = command().overview()
            report.largestRecordings shouldBe emptyList()
            report.duplicateCandidates shouldBe emptyList()
            report.estimatedReclaimableBytes shouldBe 0L
            report.totals.verifiedPhysicalBytes shouldBe null
            report.verifiedReclaimableBytes shouldBe null
        }
        expect("largest metadata files, retained aliases, removed aliases and orphan rows") {
            sql(
                listOf(
                    "INSERT INTO executed_file VALUES (1,'a',0,1,'2026-01-01','ch','a','ch',60,'SPLITTED'),(2,'b',2,9999999999,'2026-01-01','ch','b','ch',60,'SPLITTED'),(3,'c',3,1,'2026-01-01','ch','c','ch',60,'SPLITTED')",
                    "INSERT INTO program VALUES (1,'kept',1,'COMPLETED'),(2,'remove1',2,'COMPLETED'),(3,'remove2',3,'COMPLETED'),(4,'orphan',999,'REGISTERED')",
                    "INSERT INTO splitted_file VALUES (1,1,'s1',1,1,'REGISTERED'),(2,2,'s2',1,1,'REGISTERED'),(3,3,'s3',1,1,'REGISTERED')",
                    "INSERT INTO created_file VALUES (1,1,'nas/shared',3000000000,'video/mp4',NULL,'FILE_MOVED'),(2,2,'NAS/./shared',3000000000,'video/mp4',NULL,'FILE_MOVED'),(3,2,'nas/unique',50,'video/mp4',NULL,'REGISTERED'),(4,3,'NAS/unique',60,'video/mp4',NULL,'REGISTERED'),(5,3,'nas/x/../unique',70,'text/plain',NULL,'REGISTERED')",
                ),
            )
            val r = command().overview()
            r.largestRecordings.map { it.id } shouldBe listOf(2L, 1L, 3L, 4L)
            r.largestRecordings.first().registeredBytes shouldBe 3000000050L
            r.largestRecordings.last().registeredBytes shouldBe 0L
            r.duplicateCandidates
                .single()
                .retained.id shouldBe 1L
            r.duplicateCandidates
                .single()
                .removable
                .map { it.id } shouldBe listOf(2L, 3L)
            r.estimatedReclaimableBytes shouldBe 50L
            r.excludedSharedPathCount shouldBe 1L
            r.totals.registeredBytes shouldBe 6000000180L
            mapper.references().size shouldBe 6
        }
        expect("shared executed-file records count each file once per program and are never reclaimed while retained") {
            sql(
                listOf(
                    "INSERT INTO executed_file VALUES (1,'a',0,1,'2026-01-01','ch','a','ch',60,'SPLITTED')",
                    "INSERT INTO program VALUES (1,'first',1,'COMPLETED'),(2,'second',1,'COMPLETED'),(3,'third',999,'REGISTERED')",
                    "INSERT INTO splitted_file VALUES (1,1,'s',1,1,'REGISTERED')",
                    "INSERT INTO created_file VALUES (1,1,'p',100,'video/mp4',NULL,'FILE_MOVED')",
                ),
            )
            val r = command().overview()
            r.largestRecordings.map { it.id } shouldBe listOf(1L, 2L, 3L)
            r.largestRecordings.take(2).map { it.registeredBytes } shouldBe listOf(100L, 100L)
            r.totals.registeredBytes shouldBe 100L
            r.estimatedReclaimableBytes shouldBe 0L
            r.excludedSharedPathCount shouldBe 1L
        }
    }

    private fun sql(statements: List<String>) {
        dataSource.connection.use { c ->
            statements.forEach { query -> c.createStatement().use { it.execute(query) } }
            c.commit()
        }
    }
}
