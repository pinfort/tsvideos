package me.pinfort.tsvideos.catalog.persistence

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.ProgramSearchQuery
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
class ProgramSearchMapperTest : ExpectSpec() {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var mapper: ProgramMapper

    init {
        beforeTest {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    listOf("created_file", "splitted_file", "program", "executed_file").forEach { statement.execute("DELETE FROM $it") }
                    statement.execute(
                        """
                        INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) VALUES
                        (1,'one',0,100,'2026-10-01 00:00:00','BS1','t','channel',60,'SPLITTED'),
                        (2,'two',5,200,'2026-10-02 00:00:00','BS1','t','channel',30,'SPLITTED'),
                        (3,'three',-1,300,'2026-10-02 00:00:00','BS2','t','channel',90,'SPLITTED'),
                        (4,'four',2,200,'2026-10-03 00:00:00','BS1','t','channel',30,'SPLITTED'),
                        (5,'five',9,500,'2026-10-04 00:00:00','BS1','t','channel',120,'SPLITTED');
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO program(id,name,executed_file_id,status) VALUES
                        (1,'News One',1,'COMPLETED'),(2,'NEWS Two',2,'COMPLETED'),
                        (3,'Drama',3,'ERROR'),(4,'News Four',4,'REGISTERED'),(5,'100%_News',5,'COMPLETED');
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES
                        (1,1,'split1',100,60,'ENCODE_TASK_ADDED'),(2,2,'split2',200,30,'ENCODE_TASK_ADDED'),
                        (3,3,'split3',300,90,'ENCODE_TASK_ADDED'),(4,4,'split4',200,30,'ENCODE_TASK_ADDED'),
                        (5,5,'split5',500,120,'ENCODE_TASK_ADDED');
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES
                        (1,1,'ts1',100,'video/vnd.dlna.mpeg-tts','gzip','FILE_MOVED'),
                        (2,2,'mp4a',20,'video/mp4',NULL,'FILE_MOVED'),
                        (3,2,'mp4b',20,'video/mp4',NULL,'FILE_MOVED'),
                        (4,2,'ts2',200,'video/vnd.dlna.mpeg-tts','gzip','FILE_MOVED'),
                        (5,3,'pending',30,'video/mp4',NULL,'ENCODE_SUCCESS'),
                        (6,4,'null-mime',30,NULL,NULL,'FILE_MOVED'),
                        (7,4,'subtitle',30,'text/plain',NULL,'FILE_MOVED'),
                        (8,5,'registered-ts',50,'video/vnd.dlna.mpeg-tts','gzip','REGISTERED');
                        """.trimIndent(),
                    )
                }
                connection.commit()
            }
        }

        expect("combined filters share their count and honor inclusive start exclusive end") {
            val query =
                ProgramSearchQuery(
                    name = "news",
                    channel = "BS1",
                    recordedFrom = LocalDateTime.parse("2026-10-01T00:00:00"),
                    recordedTo = LocalDateTime.parse("2026-10-03T00:00:00"),
                    status = Program.Status.COMPLETED,
                    minDrops = 0,
                    maxDrops = 5,
                    fileType = ProgramSearchQuery.FileType.TS,
                )
            mapper.search(query).map { it.id } shouldBe listOf(2, 1)
            mapper.countSearch(query) shouldBe 2
            mapper.search(query.copy(recordedFrom = LocalDateTime.parse("2026-10-02T00:00:00"))).map { it.id } shouldBe listOf(2)
            mapper.search(query.copy(recordedTo = LocalDateTime.parse("2026-10-02T00:00:00"))).map { it.id } shouldBe listOf(1)
            mapper.search(query.copy(channel = "BS")).isEmpty() shouldBe true
        }

        expect("orphan programs remain searchable and nullable sort uses the same ID tie-break") {
            dataSource.connection.use { connection ->
                connection.createStatement().use {
                    it.execute(
                        "INSERT INTO program(id,name,executed_file_id,status) VALUES (6,'Orphan A',99,'ERROR'),(7,'Orphan B',98,'ERROR')",
                    )
                }
                connection.commit()
            }
            val query = ProgramSearchQuery(name = "orphan", status = Program.Status.ERROR)
            mapper.search(query).map { it.id } shouldBe listOf(7, 6)
            mapper.countSearch(query) shouldBe 2
            mapper.countSearch(ProgramSearchQuery()) shouldBe 7
            mapper.search(ProgramSearchQuery(direction = ProgramSearchQuery.Direction.ASC)).take(2).map { it.id } shouldBe listOf(6, 7)
            mapper.search(ProgramSearchQuery()).takeLast(2).map { it.id } shouldBe listOf(7, 6)
            ProgramSearchQuery.Sort.entries.forEach { sort ->
                mapper.search(query.copy(sort = sort, direction = ProgramSearchQuery.Direction.ASC)).map { it.id } shouldBe listOf(6, 7)
                mapper.search(query.copy(sort = sort, direction = ProgramSearchQuery.Direction.DESC)).map { it.id } shouldBe listOf(7, 6)
            }
            mapper.countSearch(query.copy(minDrops = 0)) shouldBe 0
            mapper.countSearch(query.copy(channel = "BS1")) shouldBe 0
            mapper.countSearch(query.copy(recordedFrom = LocalDateTime.parse("2026-10-01T00:00:00"))) shouldBe 0
            mapper.countSearch(query.copy(fileType = ProgramSearchQuery.FileType.TS)) shouldBe 0
        }

        expect("unknown drops never satisfy numeric limits including max-only zero") {
            mapper.search(ProgramSearchQuery(maxDrops = 0)).map { it.id } shouldBe listOf(1)
            mapper.search(ProgramSearchQuery(minDrops = 0)).map { it.id } shouldBe listOf(5, 4, 2, 1)
            mapper.search(ProgramSearchQuery(maxDrops = 9)).map { it.id } shouldBe listOf(5, 4, 2, 1)
            mapper.search(ProgramSearchQuery(status = Program.Status.ERROR)).map { it.id } shouldBe listOf(3)
        }

        expect("type availability excludes unfinished files and ancillary MIME without multiplying programs") {
            val encoded = ProgramSearchQuery(fileType = ProgramSearchQuery.FileType.ENCODED)
            mapper.search(encoded).map { it.id } shouldBe listOf(2)
            mapper.countSearch(encoded) shouldBe 1
            val ts = encoded.copy(fileType = ProgramSearchQuery.FileType.TS)
            mapper.search(ts).map { it.id } shouldBe listOf(2, 1)
            mapper.countSearch(ts) shouldBe 2
        }

        expect("every sort uses deterministic same-direction ID ties and paging retains total") {
            val ascending =
                mapOf(
                    ProgramSearchQuery.Sort.RECORDED_AT to listOf(1L, 2L, 3L, 4L, 5L),
                    ProgramSearchQuery.Sort.DURATION to listOf(2L, 4L, 1L, 3L, 5L),
                    ProgramSearchQuery.Sort.SIZE to listOf(1L, 2L, 4L, 3L, 5L),
                )
            ascending.forEach { (sort, ids) ->
                ProgramSearchQuery.Direction.entries.forEach { direction ->
                    val expected = if (direction == ProgramSearchQuery.Direction.ASC) ids else ids.reversed()
                    val query = ProgramSearchQuery(sort = sort, direction = direction, limit = 2, offset = 1)
                    mapper.search(query).map { it.id } shouldBe expected.drop(1).take(2)
                    mapper.countSearch(query) shouldBe 5
                    mapper.search(query.copy(offset = 5)).isEmpty() shouldBe true
                    mapper.countSearch(query.copy(offset = 5)) shouldBe 5
                }
            }
        }

        expect("name matching is case insensitive literal substring and SQL metacharacters remain data") {
            mapper.countSearch(ProgramSearchQuery(name = "nEwS")) shouldBe 4
            mapper.search(ProgramSearchQuery(name = "%_")).map { it.id } shouldBe listOf(5)
            mapper.countSearch(ProgramSearchQuery(name = "' OR 1=1 --")) shouldBe 0
            mapper.search(ProgramSearchQuery(name = "absent")).isEmpty() shouldBe true
            mapper.countSearch(ProgramSearchQuery(name = "absent")) shouldBe 0
        }
    }
}
