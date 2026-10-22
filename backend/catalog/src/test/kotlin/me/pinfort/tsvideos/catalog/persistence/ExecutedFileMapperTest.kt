package me.pinfort.tsvideos.catalog.persistence

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
class ExecutedFileMapperTest : ExpectSpec() {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var executedFileMapper: ExecutedFileMapper

    init {
        context("insert") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection.commit()

                val keyHolder = GeneratedKeyHolder()
                executedFileMapper.insert(
                    "filepath",
                    0,
                    2,
                    LocalDateTime.of(2009, 8, 3, 23, 58, 1),
                    "BSxx",
                    "myTitle",
                    "myChannel",
                    3.0,
                    "DROPCHECKED",
                    keyHolder,
                )
                connection.commit()

                keyHolder.id shouldNotBe 0
                executedFileMapper.find(keyHolder.id) shouldBe
                    ExecutedFileDto(
                        id = keyHolder.id,
                        file = "filepath",
                        drops = 0,
                        size = 2,
                        recordedAt = LocalDateTime.of(2009, 8, 3, 23, 58, 1),
                        channel = "BSxx",
                        title = "myTitle",
                        channelName = "myChannel",
                        duration = 3.0,
                        status = ExecutedFileDto.Status.DROPCHECKED,
                    )
                connection.close()
            }
        }

        context("updateStatus") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection
                    .prepareStatement(
                        """
                        INSERT INTO executed_file(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status)
                        VALUES(1,'filepath',0,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'DROPCHECKED');
                        """.trimIndent(),
                    ).execute()
                connection.commit()

                executedFileMapper.updateStatus(1, "SPLITTED")
                connection.commit()

                executedFileMapper.find(1)?.status shouldBe ExecutedFileDto.Status.SPLITTED
                connection.close()
            }
        }

        context("find") {
            expect("single") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection
                    .prepareStatement(
                        """
                        INSERT INTO executed_file(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status)
                        VALUES(1,'filepath',0,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'SPLITTED');
                        """.trimIndent(),
                    ).execute()
                connection.commit()

                val actual = executedFileMapper.find(1)
                connection.close()

                actual shouldBe
                    ExecutedFileDto(
                        id = 1,
                        file = "filepath",
                        drops = 0,
                        size = 2,
                        recordedAt = LocalDateTime.of(2009, 8, 3, 23, 58, 1),
                        channel = "BSxx",
                        title = "myTitle",
                        channelName = "myChannel",
                        duration = 3.0,
                        status = ExecutedFileDto.Status.SPLITTED,
                    )
            }

            expect("none") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection.commit()

                val actual = executedFileMapper.find(1)
                connection.close()

                actual shouldBe null
            }
        }

        context("delete") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection
                    .prepareStatement(
                        """
                        INSERT INTO executed_file(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status)
                        VALUES(1,'filepath',0,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'SPLITTED');
                        """.trimIndent(),
                    ).execute()
                connection.commit()

                executedFileMapper.delete(1)
                connection.commit()

                connection.prepareStatement("SELECT * FROM executed_file").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.fetchSize shouldBe 0
                    }
                }
                connection.close()
            }

            expect("nothingHasDeleted") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection.commit()

                executedFileMapper.delete(1)
                connection.commit()

                connection.prepareStatement("SELECT * FROM executed_file").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.fetchSize shouldBe 0
                    }
                }
                connection.close()
            }
        }

        context("selectByFile") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection
                    .prepareStatement(
                        """
                        INSERT INTO executed_file(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status)
                        VALUES(1,'filepath',0,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'SPLITTED');
                        """.trimIndent(),
                    ).execute()
                connection.commit()

                val actual = executedFileMapper.selectByFile("filepath")
                connection.close()

                actual shouldHaveSize 1
                actual[0] shouldBe
                    ExecutedFileDto(
                        id = 1,
                        file = "filepath",
                        drops = 0,
                        size = 2,
                        recordedAt = LocalDateTime.of(2009, 8, 3, 23, 58, 1),
                        channel = "BSxx",
                        title = "myTitle",
                        channelName = "myChannel",
                        duration = 3.0,
                        status = ExecutedFileDto.Status.SPLITTED,
                    )
            }

            expect("none") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection.commit()

                val actual = executedFileMapper.selectByFile("filepath")
                connection.close()

                actual shouldHaveSize 0
            }
        }
    }
}
