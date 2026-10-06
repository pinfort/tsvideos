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
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class ExecutedFileTagMapperTest : ExpectSpec() {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var executedFileTagMapper: ExecutedFileTagMapper

    init {
        beforeTest {
            dataSource.connection.use {
                it.prepareStatement("DELETE FROM executed_file_tag").execute()
                it.commit()
            }
        }

        context("insert") {
            expect("stores tags per executed file and ignores duplicates") {
                executedFileTagMapper.insert(1, "superimpose") shouldBe 1
                executedFileTagMapper.insert(1, "ews") shouldBe 1
                executedFileTagMapper.insert(1, "ews") shouldBe 0
                executedFileTagMapper.insert(2, "ews") shouldBe 1

                executedFileTagMapper.selectByExecutedFileId(1) shouldBe listOf("ews", "superimpose")
                executedFileTagMapper.selectByExecutedFileId(2) shouldBe listOf("ews")
                executedFileTagMapper.selectByExecutedFileId(3) shouldBe emptyList()
            }
        }

        context("deleteByExecutedFileId") {
            expect("deletes only the given executed file's tags") {
                executedFileTagMapper.insert(1, "ews")
                executedFileTagMapper.insert(2, "ews")

                executedFileTagMapper.deleteByExecutedFileId(1) shouldBe 1

                executedFileTagMapper.selectByExecutedFileId(1) shouldBe emptyList()
                executedFileTagMapper.selectByExecutedFileId(2) shouldBe listOf("ews")
            }
        }
    }
}
