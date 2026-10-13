package me.pinfort.tsvideos.core.external.database.mapper

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.command.InventoryExportCommand
import me.pinfort.tsvideos.core.domain.ProgramSearchQuery
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
@Import(InventoryExportCommand::class)
class InventoryExportCommandTest : ExpectSpec() {
    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var inventory: InventoryExportCommand

    init {
        beforeTest {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    listOf("created_file", "splitted_file", "program", "executed_file").forEach { statement.execute("DELETE FROM $it") }
                    statement.execute(
                        "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) VALUES(1,'raw',2,100,'2026-10-01 00:00:00','BS1','title','channel',60,'SPLITTED')",
                    )
                    statement.execute(
                        "INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(1,1,'split1',80,60,'REGISTERED'),(2,1,'split2',20,10,'REGISTERED')",
                    )
                    statement.execute(
                        "INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(1,1,'one.mp4',50,'video/mp4',NULL,'FILE_MOVED'),(2,1,'two.srt',5,'text/plain',NULL,'REGISTERED')",
                    )
                    (1..501).forEach { id ->
                        statement.addBatch("INSERT INTO program(id,name,executed_file_id,status) VALUES($id,'program$id',1,'COMPLETED')")
                    }
                    statement.executeBatch()
                    statement.execute("INSERT INTO program(id,name,executed_file_id,status) VALUES(502,'orphan',9999,'ERROR')")
                }
                connection.commit()
            }
        }
        expect("full snapshot exceeds the 500 search cap and preserves orphan/multiple-file relationships") {
            val result = inventory.export()
            result.programs.size shouldBe 502
            result.programs
                .map { it.program.id }
                .distinct()
                .size shouldBe 502
            val normal = result.programs.first { it.program.id == 1L }
            normal.executedFile!!.id shouldBe 1L
            normal.splittedFiles.map { it.id } shouldBe listOf(1L, 2L)
            normal.createdFiles.map { it.id } shouldBe listOf(1L, 2L)
            result.programs.first { it.program.id == 502L }.executedFile shouldBe null
            result.missingIds shouldBe emptyList()
        }
        expect("explicit IDs deduplicate and report missing IDs without changing the database") {
            val result = inventory.export(listOf(502, 1, 1, 999))
            result.programs.map { it.program.id } shouldBe listOf(1L, 502L)
            result.missingIds shouldBe listOf(999L)
            inventory.export().programs.size shouldBe 502
        }
        expect("shared search filters are applied across all pages") {
            inventory.export(query = ProgramSearchQuery(channel = "BS1")).programs.size shouldBe 501
            inventory.export(query = ProgramSearchQuery(name = "program501")).programs.map { it.program.id } shouldBe listOf(501L)
            inventory.export(query = ProgramSearchQuery(channel = "missing")).programs shouldBe emptyList()
        }
    }
}
