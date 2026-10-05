package me.pinfort.tsvideos.core.external.database.mapper

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import me.pinfort.tsvideos.core.external.database.dto.ProgramDto
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
class ProgramMapperTest : ExpectSpec() {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var programMapper: ProgramMapper

    init {
        context("insert") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.commit()

                val keyHolder = GeneratedKeyHolder()
                programMapper.insert("test", 1, "REGISTERED", keyHolder)
                connection.commit()

                keyHolder.id shouldNotBe 0
                programMapper.find(keyHolder.id)?.name shouldBe "test"
                connection.close()
            }
        }

        context("findByName") {
            expect("found") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(1,'test',1,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                        INSERT INTO executed_file(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status)
                        VALUES(1,'filepath',0,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'SPLITTED');
                        """.trimIndent(),
                    ).execute()
                connection.commit()

                val actual = programMapper.findByName("test")
                connection.close()

                actual shouldBe
                    ProgramDto(
                        1,
                        "test",
                        1,
                        ProgramDto.Status.REGISTERED,
                        0,
                        2,
                        LocalDateTime.of(2009, 8, 3, 23, 58, 1),
                        "BSxx",
                        "myTitle",
                        "myChannel",
                        3.0,
                    )
            }

            expect("none") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.commit()

                val actual = programMapper.findByName("test")
                connection.close()

                actual shouldBe null
            }
        }

        context("deleteByExecutedFileId") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(1,'test',1,'REGISTERED');
                """,
                    ).execute()
                connection.commit()

                programMapper.deleteByExecutedFileId(1)

                programMapper.find(1) shouldBe null
                connection.close()
            }

            expect("nothingHasDeleted") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.commit()

                programMapper.deleteByExecutedFileId(1)

                programMapper.find(1) shouldBe null
                connection.close()
            }
        }

        context("selectByName") {
            expect("single") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(1,'test',1,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(2,'esta',2,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(3,'aest',3,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                        INSERT INTO executed_file(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status)
                        VALUES(1,'filepath',0,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'SPLITTED');
                        """.trimIndent(),
                    ).execute()
                connection.commit()

                val actual = programMapper.selectByName("test")
                connection.close()

                actual shouldHaveSize 1
                actual[0] shouldBe
                    ProgramDto(
                        1,
                        "test",
                        1,
                        ProgramDto.Status.REGISTERED,
                        0,
                        2,
                        LocalDateTime.of(2009, 8, 3, 23, 58, 1),
                        "BSxx",
                        "myTitle",
                        "myChannel",
                        3.0,
                    )
            }

            expect("multiple") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(1,'test',1,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(2,'atest',2,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(3,'testa',3,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                        INSERT INTO executed_file(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status)
                        VALUES(1,'filepath',0,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'SPLITTED');
                        """.trimIndent(),
                    ).execute()
                connection
                    .prepareStatement(
                        """
                        INSERT INTO executed_file(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status)
                        VALUES(2,'filepath2',100,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'SPLITTED');
                        """.trimIndent(),
                    ).execute()
                connection.commit()

                val actual = programMapper.selectByName("test")
                connection.close()

                actual shouldHaveSize 3
                actual[0] shouldBe
                    ProgramDto(
                        1,
                        "test",
                        1,
                        ProgramDto.Status.REGISTERED,
                        0,
                        2,
                        LocalDateTime.of(2009, 8, 3, 23, 58, 1),
                        "BSxx",
                        "myTitle",
                        "myChannel",
                        3.0,
                    )
                actual[1] shouldBe
                    ProgramDto(
                        2,
                        "atest",
                        2,
                        ProgramDto.Status.REGISTERED,
                        100,
                        2,
                        LocalDateTime.of(2009, 8, 3, 23, 58, 1),
                        "BSxx",
                        "myTitle",
                        "myChannel",
                        3.0,
                    )
                actual[2] shouldBe
                    ProgramDto(
                        3,
                        "testa",
                        3,
                        ProgramDto.Status.REGISTERED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                    )
            }

            expect("none") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection.commit()

                val actual = programMapper.selectByName("test")
                connection.close()

                actual shouldHaveSize 0
            }
        }

        context("find") {
            expect("single") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(1,'test',1,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(2,'esta',2,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(3,'aest',3,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                        INSERT INTO executed_file(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status)
                        VALUES(1,'filepath',0,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'SPLITTED');
                        """.trimIndent(),
                    ).execute()
                connection.commit()

                val actual = programMapper.find(1)
                connection.close()

                actual shouldBe
                    ProgramDto(
                        1,
                        "test",
                        1,
                        ProgramDto.Status.REGISTERED,
                        0,
                        2,
                        LocalDateTime.of(2009, 8, 3, 23, 58, 1),
                        "BSxx",
                        "myTitle",
                        "myChannel",
                        3.0,
                    )
            }

            expect("none") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection.commit()

                val actual = programMapper.find(1)
                connection.close()

                actual shouldBe null
            }
        }

        context("deleteById") {
            expect("one") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(1,'test',1,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(2,'esta',2,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(3,'aest',3,'REGISTERED');
                """,
                    ).execute()
                connection.commit()

                programMapper.deleteById(1)

                programMapper.find(1) shouldBe null
                connection.close()
            }

            expect("none") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.commit()

                programMapper.deleteById(1)

                programMapper.find(1) shouldBe null
                connection.close()
            }
        }

        context("selectAll") {
            expect("only programs with executed file") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(1,'test',1,'REGISTERED'),(2,'orphan',2,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                        INSERT INTO executed_file(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status)
                        VALUES(1,'filepath',0,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'SPLITTED');
                        """.trimIndent(),
                    ).execute()
                connection.commit()

                val actual = programMapper.selectAll()
                connection.close()

                actual shouldBe
                    listOf(
                        ProgramDto(
                            1,
                            "test",
                            1,
                            ProgramDto.Status.REGISTERED,
                            0,
                            2,
                            LocalDateTime.of(2009, 8, 3, 23, 58, 1),
                            "BSxx",
                            "myTitle",
                            "myChannel",
                            3.0,
                        ),
                    )
            }
        }

        context("findByExecutedFileId") {
            expect("single") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(1,'test',1,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(2,'esta',2,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(3,'aest',3,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                        INSERT INTO executed_file(id,file,drops,`size`,recorded_at,channel,title,channelName,duration,status)
                        VALUES(1,'filepath',0,2,cast('2009-08-03 23:58:01' as datetime),'BSxx','myTitle','myChannel',3,'SPLITTED');
                        """.trimIndent(),
                    ).execute()
                connection.commit()

                val actual = programMapper.findByExecutedFileId(1)
                connection.close()

                actual shouldBe
                    ProgramDto(
                        1,
                        "test",
                        1,
                        ProgramDto.Status.REGISTERED,
                        0,
                        2,
                        LocalDateTime.of(2009, 8, 3, 23, 58, 1),
                        "BSxx",
                        "myTitle",
                        "myChannel",
                        3.0,
                    )
            }

            expect("none") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection.prepareStatement("DELETE FROM executed_file").execute()
                connection.commit()

                val actual = programMapper.findByExecutedFileId(1)
                connection.close()

                actual shouldBe null
            }
        }

        context("updateStatusByExecutedFileId") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(1,'test',1,'REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(2,'esta',2,'REGISTERED');
                """,
                    ).execute()
                connection.commit()

                programMapper.updateStatusByExecutedFileId(1, "COMPLETED")
                connection.commit()

                programMapper.find(1)?.status shouldBe ProgramDto.Status.COMPLETED
                programMapper.find(2)?.status shouldBe ProgramDto.Status.REGISTERED
                connection.close()
            }

            expect("nothingHasUpdated") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM program").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO program(id,name,executed_file_id,status) VALUES(1,'test',1,'REGISTERED');
                """,
                    ).execute()
                connection.commit()

                programMapper.updateStatusByExecutedFileId(2, "COMPLETED")
                connection.commit()

                programMapper.find(1)?.status shouldBe ProgramDto.Status.REGISTERED
                connection.close()
            }
        }
    }
}
