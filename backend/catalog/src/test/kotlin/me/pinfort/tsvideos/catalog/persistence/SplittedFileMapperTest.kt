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
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class SplittedFileMapperTest : ExpectSpec() {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var splittedFileMapper: SplittedFileMapper

    init {
        context("insert") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection.commit()

                val keyHolder = GeneratedKeyHolder()
                splittedFileMapper.insert(1, "filepath", 2, 3.0, "REGISTERED", keyHolder)
                connection.commit()

                keyHolder.id shouldNotBe 0
                splittedFileMapper.selectByExecutedFileId(1) shouldBe
                    listOf(
                        SplittedFileDto(
                            id = keyHolder.id,
                            executedFileId = 1,
                            file = "filepath",
                            size = 2,
                            duration = 3.0,
                            status = SplittedFileDto.Status.REGISTERED,
                        ),
                    )
                connection.close()
            }
        }

        context("updateStatus") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(1,1,'filepath',2,3,'REGISTERED');
                """,
                    ).execute()
                connection.commit()

                splittedFileMapper.updateStatus(1, "COMPRESS_SAVED")
                connection.commit()

                splittedFileMapper.selectByExecutedFileId(1)[0].status shouldBe SplittedFileDto.Status.COMPRESS_SAVED
                connection.close()
            }
        }

        context("selectByExecutedFileId") {
            expect("single") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(1,1,'filepath',2,3,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(2,2,'filepath2',2,3,'REGISTERED');
                """,
                    ).execute()
                connection.commit()

                val actual = splittedFileMapper.selectByExecutedFileId(1)
                connection.close()

                actual shouldHaveSize 1
                actual[0] shouldBe
                    SplittedFileDto(
                        id = 1,
                        executedFileId = 1,
                        file = "filepath",
                        size = 2,
                        duration = 3.0,
                        status = SplittedFileDto.Status.REGISTERED,
                    )
            }

            expect("multiple") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(1,1,'filepath',2,3,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(2,1,'filepath2',2,3,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(3,2,'filepath3',2,3,'REGISTERED');
                """,
                    ).execute()
                connection.commit()

                val actual = splittedFileMapper.selectByExecutedFileId(1)
                connection.close()

                actual shouldHaveSize 2
                actual[0] shouldBe
                    SplittedFileDto(
                        id = 1,
                        executedFileId = 1,
                        file = "filepath",
                        size = 2,
                        duration = 3.0,
                        status = SplittedFileDto.Status.REGISTERED,
                    )
                actual[1] shouldBe
                    SplittedFileDto(
                        id = 2,
                        executedFileId = 1,
                        file = "filepath2",
                        size = 2,
                        duration = 3.0,
                        status = SplittedFileDto.Status.REGISTERED,
                    )
            }

            expect("none") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection.commit()

                val actual = splittedFileMapper.selectByExecutedFileId(1)
                connection.close()

                actual shouldHaveSize 0
            }
        }

        context("selectByFile") {
            expect("single") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(1,1,'filepath',2,3,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(2,1,'filepath2',2,3,'REGISTERED');
                """,
                    ).execute()
                connection.commit()

                val actual = splittedFileMapper.selectByFile("filepath")
                connection.close()

                actual shouldHaveSize 1
                actual[0] shouldBe
                    SplittedFileDto(
                        id = 1,
                        executedFileId = 1,
                        file = "filepath",
                        size = 2,
                        duration = 3.0,
                        status = SplittedFileDto.Status.REGISTERED,
                    )
            }

            expect("none") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection.commit()

                val actual = splittedFileMapper.selectByFile("filepath")
                connection.close()

                actual shouldHaveSize 0
            }
        }

        context("delete") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(1,1,'filepath',2,3,'REGISTERED');
                """,
                    ).execute()
                connection.commit()

                splittedFileMapper.delete(1)
                connection.commit()

                connection.prepareStatement("SELECT * FROM splitted_file").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.fetchSize shouldBe 0
                    }
                }
                connection.close()
            }

            expect("nothingHasDeleted") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection.commit()

                splittedFileMapper.delete(1)
                connection.commit()

                connection.prepareStatement("SELECT * FROM splitted_file").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.fetchSize shouldBe 0
                    }
                }
                connection.close()
            }
        }
    }
}
