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
import java.time.LocalDateTime
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class ExecutedFileCheckMapperTest : ExpectSpec() {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var executedFileCheckMapper: ExecutedFileCheckMapper

    init {
        beforeTest {
            dataSource.connection.use {
                it.prepareStatement("DELETE FROM executed_file_check").execute()
                it.commit()
            }
        }

        context("upsert") {
            expect("records each checker once per executed file") {
                val checkedAt = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
                executedFileCheckMapper.upsert(1, "emergency_broadcast", checkedAt)
                executedFileCheckMapper.upsert(1, "emergency_broadcast", checkedAt.plusDays(1))
                executedFileCheckMapper.upsert(1, "l_shape", checkedAt)

                executedFileCheckMapper.selectByExecutedFileId(1) shouldBe listOf("emergency_broadcast", "l_shape")
                executedFileCheckMapper.selectByExecutedFileId(2) shouldBe emptyList()
            }
        }

        context("deleteByExecutedFileId") {
            expect("deletes only the given executed file's checks") {
                val checkedAt = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
                executedFileCheckMapper.upsert(1, "emergency_broadcast", checkedAt)
                executedFileCheckMapper.upsert(2, "emergency_broadcast", checkedAt)

                executedFileCheckMapper.deleteByExecutedFileId(1) shouldBe 1

                executedFileCheckMapper.selectByExecutedFileId(1) shouldBe emptyList()
                executedFileCheckMapper.selectByExecutedFileId(2) shouldBe listOf("emergency_broadcast")
            }
        }
    }
}
