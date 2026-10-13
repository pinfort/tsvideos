package me.pinfort.tsvideos.core.external.database.mapper

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.command.LibraryStatsCommand
import me.pinfort.tsvideos.core.domain.LibraryStatsQuery
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import java.time.LocalDateTime
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class LibraryStatsMapperTest : ExpectSpec() {
    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var mapper: LibraryStatsMapper

    init {
        beforeTest {
            execute(listOf("created_file", "splitted_file", "program", "executed_file").map { "DELETE FROM $it" })
        }
        expect("empty totals are zero with unknown physical usage") {
            val result = LibraryStatsCommand(mapper).summarize()
            result.totals.programCount shouldBe 0L
            result.totals.registeredBytes shouldBe 0L
            result.totals.verifiedPhysicalBytes shouldBe null
            result.groups shouldBe emptyList()
        }
        expect("counts programs once and files once across shared executed records") {
            seed()
            val t = LibraryStatsCommand(mapper).summarize().totals
            t.programCount shouldBe 4L
            t.failedProgramCount shouldBe 1L
            t.droppedFrameCount shouldBe 14L
            t.unknownDropsProgramCount shouldBe 2L
            t.registeredFileCount shouldBe 5L
            t.registeredBytes shouldBe 150L
            t.originalTsBytes shouldBe 10L
            t.encodedBytes shouldBe 20L
            t.otherBytes shouldBe 120L
            t.movedFileBytes shouldBe 10L
            t.pendingFileBytes shouldBe 140L
            t.verifiedPhysicalBytes shouldBe null
        }
        expect("groups retain unknown date and channel while deduplicating files") {
            seed()
            val command = LibraryStatsCommand(mapper)
            val channels = command.summarize(LibraryStatsQuery(LibraryStatsQuery.Group.CHANNEL)).groups
            channels.map { it.key } shouldBe listOf(null, "日本語", "空")
            channels[1].totals.registeredBytes shouldBe 150L
            command.summarize(LibraryStatsQuery(LibraryStatsQuery.Group.MONTH)).groups.map { it.key } shouldBe
                listOf(null, "2026-01", "2026-02")
        }
        expect("date bounds include start exclude end and exclude orphan dates") {
            seed()
            val command = LibraryStatsCommand(mapper)
            val start = LocalDateTime.parse("2026-01-01T00:00:00")
            val end = LocalDateTime.parse("2026-02-01T00:00:00")
            val result = command.summarize(LibraryStatsQuery(recordedFrom = start, recordedTo = end))
            result.totals.programCount shouldBe 2L
            result.totals.registeredBytes shouldBe 150L
            command.summarize(LibraryStatsQuery(recordedFrom = end)).totals.programCount shouldBe 1L
        }
    }

    private fun execute(sql: List<String>) {
        dataSource.connection.use { c ->
            sql.forEach { query -> c.createStatement().use { it.execute(query) } }
            c.commit()
        }
    }

    private fun seed() =
        execute(
            listOf(
                "INSERT INTO executed_file VALUES (1,'a',7,100,'2026-01-01','日本語','a','a',1,'SPLITTED'),(2,'b',-1,100,'2026-02-01','空','b','b',1,'SPLITTED')",
                "INSERT INTO program VALUES (1,'a',1,'ERROR'),(2,'b',1,'COMPLETED'),(3,'c',2,'REGISTERED'),(4,'orphan',999,'REGISTERED')",
                "INSERT INTO splitted_file VALUES (1,1,'split',100,1,'REGISTERED')",
                "INSERT INTO created_file VALUES (1,1,'a',10,'video/vnd.dlna.mpeg-tts','gzip','FILE_MOVED'),(2,1,'b',20,'video/mp4',NULL,'ENCODE_SUCCESS'),(3,1,'c',30,'video/vnd.dlna.mpeg-tts',NULL,'REGISTERED'),(4,1,'d',40,'text/plain',NULL,'REGISTERED'),(5,1,'e',50,NULL,NULL,'REGISTERED')",
            ),
        )
}
