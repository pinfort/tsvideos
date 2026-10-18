package me.pinfort.tsvideos.core.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.domain.DuplicateLibraryNameException
import me.pinfort.tsvideos.core.domain.LibraryItemNotFoundException
import me.pinfort.tsvideos.core.domain.LibraryKind
import me.pinfort.tsvideos.core.domain.ProgramSearchQuery
import me.pinfort.tsvideos.core.external.database.mapper.LibraryOrganizationMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
@Import(LibraryOrganizationCommand::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LibraryOrganizationCommandTest : ExpectSpec() {
    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var command: LibraryOrganizationCommand

    @Autowired private lateinit var mapper: LibraryOrganizationMapper

    @Autowired private lateinit var programs: ProgramMapper

    private fun sql(sql: String) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.executeUpdate(sql) }
        }
    }

    private fun count(sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun seed() {
        listOf("library_collection", "library_tag", "executed_file_tag", "program", "executed_file").forEach { sql("DELETE FROM $it") }
        for (id in 1..3) {
            sql(
                "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                    "VALUES($id,'original$id',0,10,'2026-01-0${if (id == 1) 1 else 2}','BS1','title','channel',10,'SPLITTED')",
            )
            sql("INSERT INTO program(id,name,executed_file_id,status) VALUES($id,'episode$id',$id,'COMPLETED')")
        }
    }

    init {
        expect("names normalize Japanese NFC and case, persist and reject duplicate rename") {
            seed()
            val first = command.create(LibraryKind.COLLECTION, "  日本語シリーズ  ")
            first.name shouldBe "日本語シリーズ"
            val accent = command.create(LibraryKind.COLLECTION, "Cafe\u0301")
            accent.name shouldBe "Café"
            shouldThrow<DuplicateLibraryNameException> { command.create(LibraryKind.COLLECTION, "CAFÉ") }
            shouldThrow<DuplicateLibraryNameException> { command.rename(LibraryKind.COLLECTION, first.id, "café") }
            command.find(LibraryKind.COLLECTION, first.id).name shouldBe "日本語シリーズ"
            command.rename(LibraryKind.COLLECTION, first.id, "番組集").name shouldBe "番組集"
            LibraryOrganizationCommand(mapper).list(LibraryKind.COLLECTION).map { it.name }.toSet() shouldBe setOf("番組集", "Café")
            command.create(LibraryKind.TAG, "番組集").name shouldBe "番組集"
            command.create(LibraryKind.TAG, "か\u3099").name shouldBe "が"
            shouldThrow<DuplicateLibraryNameException> { command.create(LibraryKind.TAG, "が") }
        }
        expect("renaming a label to a case-only variant of its own name is not a conflict") {
            seed()
            val tag = command.create(LibraryKind.TAG, "favorite")
            command.rename(LibraryKind.TAG, tag.id, "Favorite").name shouldBe "Favorite"
            command.list(LibraryKind.TAG).map { it.id } shouldBe listOf(tag.id)
        }
        expect("manual tags are independent of detector tags in executed_file_tag") {
            seed()
            sql("INSERT INTO executed_file_tag(executed_file_id,tag) VALUES(1,'ews')")
            val tag = command.assignTag(1, "ews")
            command.forProgram(1).tags.map { it.name } shouldBe listOf("ews")
            command.delete(LibraryKind.TAG, tag.id)
            programs.deleteById(1)
            count("SELECT COUNT(*) FROM executed_file_tag WHERE executed_file_id=1 AND tag='ews'") shouldBe 1
        }
        expect("blank control oversized names and unknown IDs are rejected without creating orphan tags") {
            seed()
            for (name in listOf("", " ", "line\nbreak", "x".repeat(101))) {
                shouldThrow<IllegalArgumentException> {
                    command.create(LibraryKind.TAG, name)
                }
            }
            shouldThrow<IllegalArgumentException> { command.find(LibraryKind.TAG, 0) }
            shouldThrow<LibraryItemNotFoundException> { command.assign(LibraryKind.TAG, 9999, 1, true) }
            shouldThrow<LibraryItemNotFoundException> { command.assignTag(9999, "never persisted") }
            command.list(LibraryKind.TAG).size shouldBe 0
            shouldThrow<LibraryItemNotFoundException> { command.forProgram(9999) }
        }
        expect("assignment and removal are idempotent and tag-by-name reuses normalized identity") {
            seed()
            val collection = command.create(LibraryKind.COLLECTION, "collection")
            command.assign(LibraryKind.COLLECTION, collection.id, 1, true)
            command.assign(LibraryKind.COLLECTION, collection.id, 1, true)
            command
                .forProgram(1)
                .collections
                .single()
                .id shouldBe collection.id
            command.find(LibraryKind.COLLECTION, collection.id).programCount shouldBe 1
            val tag = command.assignTag(1, "Favorite")
            command.assignTag(1, " favorite ").id shouldBe tag.id
            command.forProgram(1).tags.size shouldBe 1
            command.assign(LibraryKind.COLLECTION, collection.id, 1, false)
            command.assign(LibraryKind.COLLECTION, collection.id, 1, false)
            command.forProgram(1).collections.size shouldBe 0
        }
        expect("shared search composes collection and tag with recording order stable ties count and paging") {
            seed()
            val collection = command.create(LibraryKind.COLLECTION, "series")
            (1L..3L).forEach { command.assign(LibraryKind.COLLECTION, collection.id, it, true) }
            val tag = command.assignTag(2, "pick")
            command.assign(LibraryKind.TAG, tag.id, 3, true)
            val query =
                ProgramSearchQuery(
                    collectionId = collection.id,
                    libraryTagId = tag.id,
                    name = "episode",
                    channel = "BS1",
                    limit = 1,
                    offset = 1,
                )
            programs.countSearch(query) shouldBe 2
            programs.search(query).map { it.id } shouldBe listOf(2L)
            programs.search(query.copy(libraryTagId = null, limit = 10, offset = 0)).map { it.id } shouldBe listOf(3L, 2L, 1L)
            programs.countSearch(query.copy(collectionId = 9999)) shouldBe 0
            command.delete(LibraryKind.COLLECTION, collection.id)
            programs.countSearch(query) shouldBe 0
            programs.find(2)?.id shouldBe 2
            command
                .forProgram(2)
                .tags
                .single()
                .id shouldBe tag.id
        }
        expect("deleting recordings through both existing mapper paths cascades only their associations") {
            seed()
            val collection = command.create(LibraryKind.COLLECTION, "series")
            (1L..3L).forEach {
                command.assign(LibraryKind.COLLECTION, collection.id, it, true)
                command.assignTag(it, "tag")
            }
            programs.deleteById(1)
            programs.deleteByExecutedFileId(2)
            command.find(LibraryKind.COLLECTION, collection.id).programCount shouldBe 1
            mapper.forProgram(LibraryKind.COLLECTION, 1).size shouldBe 0
            mapper.forProgram(LibraryKind.TAG, 2).size shouldBe 0
            command.list(LibraryKind.TAG).single().programCount shouldBe 1
            command.delete(LibraryKind.TAG, command.list(LibraryKind.TAG).single().id)
            programs.find(3)?.id shouldBe 3
            command
                .forProgram(3)
                .collections
                .single()
                .id shouldBe collection.id
        }
        expect("concurrent assignment uses one persistent association") {
            seed()
            val collection = command.create(LibraryKind.COLLECTION, "series")
            val pool = Executors.newFixedThreadPool(4)
            try {
                val tasks = (1..4).map { pool.submit { command.assign(LibraryKind.COLLECTION, collection.id, 1, true) } }
                tasks.forEach { it.get(15, TimeUnit.SECONDS) }
                command.find(LibraryKind.COLLECTION, collection.id).programCount shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }
        expect("concurrent tag-by-name with one new name creates a single reused tag") {
            seed()
            val pool = Executors.newFixedThreadPool(3)
            try {
                val tasks = (1L..3L).map { id -> pool.submit<Long> { command.assignTag(id, if (id == 2L) "NEW" else "new").id } }
                tasks.map { it.get(15, TimeUnit.SECONDS) }.toSet().size shouldBe 1
                command.list(LibraryKind.TAG).single().programCount shouldBe 3
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
