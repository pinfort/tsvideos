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
class CreatedFileMapperTest : ExpectSpec() {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var createdFileMapper: CreatedFileMapper

    init {
        context("insert") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection.commit()

                val keyHolder = GeneratedKeyHolder()
                createdFileMapper.insert(2, "test", 3, "test2", "test3", "FILE_MOVED", keyHolder)
                connection.commit()

                keyHolder.id shouldNotBe 0
                createdFileMapper.find(keyHolder.id) shouldBe
                    CreatedFileDto(
                        id = keyHolder.id,
                        splittedFileId = 2,
                        file = "test",
                        size = 3,
                        mime = "test2",
                        encoding = "test3",
                        status = CreatedFileDto.Status.FILE_MOVED,
                    )
                connection.close()
            }

            expect("nullableMimeAndEncoding") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection.commit()

                val keyHolder = GeneratedKeyHolder()
                createdFileMapper.insert(2, "test", 3, null, null, "REGISTERED", keyHolder)
                connection.commit()

                createdFileMapper.find(keyHolder.id) shouldBe
                    CreatedFileDto(
                        id = keyHolder.id,
                        splittedFileId = 2,
                        file = "test",
                        size = 3,
                        mime = null,
                        encoding = null,
                        status = CreatedFileDto.Status.REGISTERED,
                    )
                connection.close()
            }
        }

        context("resolution") {
            expect("is written on insert and read back by every select") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection
                    .prepareStatement(
                        "INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(2,5,'s',6,7.0,'COMPRESS_SAVED')",
                    ).execute()
                connection.commit()

                val keyHolder = GeneratedKeyHolder()
                createdFileMapper.insert(2, "test", 3, "video/mp4", null, "FILE_MOVED", keyHolder, 1920, 1080)
                connection.commit()

                val expected = CreatedFileDto(keyHolder.id, 2, "test", 3, "video/mp4", null, CreatedFileDto.Status.FILE_MOVED, 1920, 1080)
                createdFileMapper.find(keyHolder.id) shouldBe expected
                createdFileMapper.selectBySplittedFileId(2) shouldBe listOf(expected)
                createdFileMapper.selectByExecutedFileId(5) shouldBe listOf(expected)
                createdFileMapper.selectAllWithExecutedFileId().map { it.toCreatedFileDto() } shouldBe listOf(expected)
                createdFileMapper.selectMovedByExecutedFileIds(listOf(5)).map { it.toCreatedFileDto() } shouldBe listOf(expected)
                connection.close()
            }
        }

        context("updateResolutionIfMissing") {
            expect("sets a missing resolution and leaves a stored one alone") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection
                    .prepareStatement(
                        "INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status,width,height) " +
                            "VALUES(1,2,'a',3,'video/mp4',NULL,'FILE_MOVED',NULL,NULL),(2,2,'b',3,'video/mp4',NULL,'FILE_MOVED',1920,1080)",
                    ).execute()
                connection.commit()

                createdFileMapper.updateResolutionIfMissing(1, 1280, 720) shouldBe 1
                createdFileMapper.updateResolutionIfMissing(2, 1280, 720) shouldBe 0
                connection.commit()

                createdFileMapper.find(1)?.let { it.width to it.height } shouldBe (1280 to 720)
                createdFileMapper.find(2)?.let { it.width to it.height } shouldBe (1920 to 1080)
                connection.close()
            }
        }

        context("updateMimeIfMissing") {
            expect("sets a missing mime and leaves a stored one alone") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection
                    .prepareStatement(
                        "INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status,width,height) " +
                            "VALUES(1,2,'a.ass',3,NULL,NULL,'FILE_MOVED',NULL,NULL),(2,2,'b.ass',3,'text/plain',NULL,'FILE_MOVED',NULL,NULL)",
                    ).execute()
                connection.commit()

                createdFileMapper.updateMimeIfMissing(1, "text/x-ssa") shouldBe 1
                createdFileMapper.updateMimeIfMissing(2, "text/x-ssa") shouldBe 0
                connection.commit()

                createdFileMapper.find(1)?.mime shouldBe "text/x-ssa"
                createdFileMapper.find(2)?.mime shouldBe "text/plain"
                connection.close()
            }
        }

        context("find") {
            expect("single") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(1,2,'test',3,'test2','test3','REGISTERED');
                """,
                    ).execute()
                connection.commit()

                val actual = createdFileMapper.find(1)
                connection.close()

                actual shouldBe
                    CreatedFileDto(
                        id = 1,
                        splittedFileId = 2,
                        file = "test",
                        size = 3,
                        mime = "test2",
                        encoding = "test3",
                        status = CreatedFileDto.Status.REGISTERED,
                    )
            }

            expect("none") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection.commit()

                val actual = createdFileMapper.find(1)
                connection.close()

                actual shouldBe null
            }
        }

        context("selectBySplittedFileId") {
            expect("single") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(1,2,'test',3,'test2','test3','REGISTERED');
                """,
                    ).execute()
                connection.commit()

                val actual = createdFileMapper.selectBySplittedFileId(2)
                connection.close()

                actual shouldHaveSize 1
                actual[0] shouldBe
                    CreatedFileDto(
                        id = 1,
                        splittedFileId = 2,
                        file = "test",
                        size = 3,
                        mime = "test2",
                        encoding = "test3",
                        status = CreatedFileDto.Status.REGISTERED,
                    )
            }

            expect("multiple") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(1,2,'test',3,'test2','test3','REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(2,2,'test4',4,'test5','test6','ENCODE_SUCCESS');
                """,
                    ).execute()
                connection.commit()

                val actual = createdFileMapper.selectBySplittedFileId(2)
                connection.close()

                actual shouldHaveSize 2
                actual[0] shouldBe
                    CreatedFileDto(
                        id = 1,
                        splittedFileId = 2,
                        file = "test",
                        size = 3,
                        mime = "test2",
                        encoding = "test3",
                        status = CreatedFileDto.Status.REGISTERED,
                    )
                actual[1] shouldBe
                    CreatedFileDto(
                        id = 2,
                        splittedFileId = 2,
                        file = "test4",
                        size = 4,
                        mime = "test5",
                        encoding = "test6",
                        status = CreatedFileDto.Status.ENCODE_SUCCESS,
                    )
            }

            expect("none") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection.commit()

                val actual = createdFileMapper.selectBySplittedFileId(1)
                connection.close()

                actual shouldHaveSize 0
            }
        }

        context("selectAllWithExecutedFileId") {
            expect("joins the executed file ID, orders by ID and skips files without a split") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection
                    .prepareStatement(
                        "INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) " +
                            "VALUES(1,5,'s1',6,7.0,'COMPRESS_SAVED'),(2,6,'s2',6,7.0,'COMPRESS_SAVED')",
                    ).execute()
                connection
                    .prepareStatement(
                        "INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) " +
                            "VALUES(9,2,'b',4,NULL,NULL,'FILE_MOVED'),(8,1,'a',3,'video/mp4',NULL,'REGISTERED')," +
                            "(10,99,'orphan',1,NULL,NULL,'FILE_MOVED')",
                    ).execute()
                connection.commit()

                val actual = createdFileMapper.selectAllWithExecutedFileId()
                connection.close()

                actual shouldBe
                    listOf(
                        ExecutedCreatedFileDto(5, 8, 1, "a", 3, "video/mp4", null, CreatedFileDto.Status.REGISTERED),
                        ExecutedCreatedFileDto(6, 9, 2, "b", 4, null, null, CreatedFileDto.Status.FILE_MOVED),
                    )
                actual[0].toCreatedFileDto() shouldBe
                    CreatedFileDto(8, 1, "a", 3, "video/mp4", null, CreatedFileDto.Status.REGISTERED)
            }
        }

        context("selectByExecutedFileId") {
            expect("single") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(1,5,'test6',6,7.0,'COMPRESS_SAVED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(7,1,'test',3,'test2','test3','REGISTERED');
                """,
                    ).execute()
                connection.commit()

                val actual = createdFileMapper.selectByExecutedFileId(5)
                connection.close()

                actual shouldHaveSize 1
                actual[0] shouldBe
                    CreatedFileDto(
                        id = 7,
                        splittedFileId = 1,
                        file = "test",
                        size = 3,
                        mime = "test2",
                        encoding = "test3",
                        status = CreatedFileDto.Status.REGISTERED,
                    )
            }

            expect("multiple") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(1,5,'test6',6,7.0,'COMPRESS_SAVED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(2,5,'test7',6,7.0,'ENCODE_TASK_ADDED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(3,6,'test8',6,7.0,'COMPRESS_SAVED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(7,2,'test',3,'test2','test3','REGISTERED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(8,2,'test4',4,'test5','test6','ENCODE_SUCCESS');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(9,3,'test9',4,'test10','test11','ENCODE_SUCCESS');
                """,
                    ).execute()
                connection.commit()

                val actual = createdFileMapper.selectByExecutedFileId(5)
                connection.close()

                actual shouldHaveSize 2
                actual[0] shouldBe
                    CreatedFileDto(
                        id = 7,
                        splittedFileId = 2,
                        file = "test",
                        size = 3,
                        mime = "test2",
                        encoding = "test3",
                        status = CreatedFileDto.Status.REGISTERED,
                    )
                actual[1] shouldBe
                    CreatedFileDto(
                        id = 8,
                        splittedFileId = 2,
                        file = "test4",
                        size = 4,
                        mime = "test5",
                        encoding = "test6",
                        status = CreatedFileDto.Status.ENCODE_SUCCESS,
                    )
            }

            expect("none") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection.prepareStatement("DELETE FROM splitted_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(1,4,'test6',6,7.0,'COMPRESS_SAVED');
                """,
                    ).execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(7,1,'test',3,'test2','test3','REGISTERED');
                """,
                    ).execute()
                connection.commit()

                val actual = createdFileMapper.selectByExecutedFileId(5)
                connection.close()

                actual shouldHaveSize 0
            }
        }

        context("updateFile") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(7,1,'test',3,'test2','test3','REGISTERED');
                """,
                    ).execute()
                connection.commit()

                createdFileMapper.updateFile(7, "test4")
                connection.commit()

                createdFileMapper.find(7)?.file shouldBe "test4"
                connection.close()
            }

            expect("nothingHasUpdated") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(7,1,'test',3,'test2','test3','REGISTERED');
                """,
                    ).execute()
                connection.commit()

                createdFileMapper.updateFile(6, "test4")
                connection.commit()

                createdFileMapper.find(7)?.file shouldBe "test"
                connection.close()
            }
        }

        context("updateStatus") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(7,1,'test',3,'test2','test3','ENCODE_SUCCESS');
                """,
                    ).execute()
                connection.commit()

                createdFileMapper.updateStatus(7, "FILE_MOVED")
                connection.commit()

                createdFileMapper.find(7)?.status shouldBe CreatedFileDto.Status.FILE_MOVED
                connection.close()
            }

            expect("nothingHasUpdated") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(7,1,'test',3,'test2','test3','ENCODE_SUCCESS');
                """,
                    ).execute()
                connection.commit()

                createdFileMapper.updateStatus(6, "FILE_MOVED")
                connection.commit()

                createdFileMapper.find(7)?.status shouldBe CreatedFileDto.Status.ENCODE_SUCCESS
                connection.close()
            }
        }

        context("delete") {
            expect("success") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection
                    .prepareStatement(
                        """
                    INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES(7,1,'test',3,'test2','test3','REGISTERED');
                """,
                    ).execute()
                connection.commit()

                createdFileMapper.delete(7)
                connection.commit()

                connection.prepareStatement("SELECT * FROM created_file").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.fetchSize shouldBe 0
                    }
                }
                connection.close()
            }

            expect("nothingHasDeleted") {
                val connection = dataSource.connection
                connection.prepareStatement("DELETE FROM created_file").execute()
                connection.commit()

                createdFileMapper.delete(7)
                connection.commit()

                connection.prepareStatement("SELECT * FROM created_file").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.fetchSize shouldBe 0
                    }
                }
                connection.close()
            }
        }
    }
}
