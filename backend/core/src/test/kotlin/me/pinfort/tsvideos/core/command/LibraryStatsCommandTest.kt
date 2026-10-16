package me.pinfort.tsvideos.core.command

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.domain.LibraryStatsQuery
import me.pinfort.tsvideos.core.domain.LibraryStatsQuery.Group
import me.pinfort.tsvideos.core.external.database.mapper.LibraryStatsMapper
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@MybatisTest
@Import(LibraryStatsCommand::class, LibraryStatsCommandTest.ObservedMapper::class)
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class LibraryStatsCommandTest : ExpectSpec() {
    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var command: LibraryStatsCommand

    /** Wraps the MyBatis mapper so a test can act between the two queries of one summarize call. */
    @TestConfiguration
    class ObservedMapper {
        @Bean
        @Primary
        fun observedLibraryStatsMapper(
            @Qualifier("libraryStatsMapper") delegate: LibraryStatsMapper,
        ): LibraryStatsMapper =
            object : LibraryStatsMapper by delegate {
                override fun programs(query: LibraryStatsQuery) = delegate.programs(query).also { afterPrograms() }
            }
    }

    init {
        afterTest { afterPrograms = {} }
        beforeTest {
            afterPrograms = {}
            execute(listOf("created_file", "splitted_file", "program", "executed_file").map { "DELETE FROM $it" })
        }
        expect("both queries read one read-only repeatable-read snapshot") {
            seed()
            var observed: Triple<Boolean, Boolean, Int?>? = null
            afterPrograms = {
                afterPrograms = {}
                observed =
                    Triple(
                        TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    )
                execute(
                    listOf(
                        "INSERT INTO created_file (id, splitted_file_id, file, size, mime, encoding, status) VALUES (6,1,'late',1000,'video/mp4',NULL,'REGISTERED')",
                    ),
                )
            }
            val totals = command.summarize().totals
            observed shouldBe Triple(true, true, Isolation.REPEATABLE_READ.value())
            totals.registeredBytes shouldBe 150L
            command.summarize().totals.registeredBytes shouldBe 1150L
        }
        expect("empty totals are zero with unknown physical usage") {
            val result = command.summarize()
            result.totals.programCount shouldBe 0L
            result.totals.registeredBytes shouldBe 0L
            result.totals.verifiedPhysicalBytes shouldBe null
            result.groups shouldBe emptyList()
        }
        expect("counts programs once and files once across shared executed records") {
            seed()
            val t = command.summarize().totals
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
            val channels = command.summarize(LibraryStatsQuery(LibraryStatsQuery.Group.CHANNEL)).groups
            channels.map { it.key } shouldBe listOf(null, "日本語", "空")
            channels[1].totals.registeredBytes shouldBe 150L
            channels[1].totals.droppedFrameCount shouldBe 14L
            channels[2].totals.droppedFrameCount shouldBe 0L
            channels[2].totals.unknownDropsProgramCount shouldBe 1L
            val months = command.summarize(LibraryStatsQuery(LibraryStatsQuery.Group.MONTH)).groups
            months.map { it.key } shouldBe listOf(null, "2026-01", "2026-02")
            months.map { it.totals.programCount } shouldBe listOf(1L, 2L, 1L)
            months.map { it.totals.registeredBytes } shouldBe listOf(0L, 150L, 0L)
            months[1].totals.failedProgramCount shouldBe 1L
        }
        expect("date bounds include start exclude end and exclude orphan dates") {
            seed()
            val start = LocalDateTime.parse("2026-01-01T00:00:00")
            val end = LocalDateTime.parse("2026-02-01T00:00:00")
            val result = command.summarize(LibraryStatsQuery(recordedFrom = start, recordedTo = end))
            result.totals.programCount shouldBe 2L
            result.totals.registeredBytes shouldBe 150L
            command.summarize(LibraryStatsQuery(recordedFrom = end)).totals.programCount shouldBe 1L
            // The orphan program (no executed_file) has no recording time, so any bound excludes it.
            command.summarize(LibraryStatsQuery(Group.CHANNEL, recordedTo = end)).groups.map { it.key } shouldBe listOf("日本語")
            command.summarize(LibraryStatsQuery(recordedTo = end)).totals.let {
                it.programCount shouldBe 2L
                it.registeredBytes shouldBe 150L
            }
        }
    }

    private fun execute(sql: List<String>) {
        dataSource.connection.use { c ->
            sql.forEach { query -> c.createStatement().use { it.execute(query) } }
            c.commit()
        }
    }

    private companion object {
        var afterPrograms: () -> Unit = {}
    }

    private fun seed() =
        execute(
            listOf(
                "INSERT INTO executed_file (id, file, drops, size, recorded_at, channel, title, channelName, duration, status) VALUES (1,'a',7,100,'2026-01-01','日本語','a','a',1,'SPLITTED'),(2,'b',-1,100,'2026-02-01','空','b','b',1,'SPLITTED')",
                "INSERT INTO program (id, name, executed_file_id, status) VALUES (1,'a',1,'ERROR'),(2,'b',1,'COMPLETED'),(3,'c',2,'REGISTERED'),(4,'orphan',999,'REGISTERED')",
                "INSERT INTO splitted_file (id, executed_file_id, file, size, duration, status) VALUES (1,1,'split',100,1,'REGISTERED')",
                "INSERT INTO created_file (id, splitted_file_id, file, size, mime, encoding, status) VALUES (1,1,'a',10,'video/vnd.dlna.mpeg-tts','gzip','FILE_MOVED'),(2,1,'b',20,'video/mp4',NULL,'ENCODE_SUCCESS'),(3,1,'c',30,'video/vnd.dlna.mpeg-tts',NULL,'REGISTERED'),(4,1,'d',40,'text/plain',NULL,'REGISTERED'),(5,1,'e',50,NULL,NULL,'REGISTERED')",
            ),
        )
}
