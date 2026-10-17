package me.pinfort.tsvideos.core.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import jcifs.SmbResource
import jcifs.smb.SmbException
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.external.database.dto.CreatedFileDto
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.GeneratedKeyHolder
import me.pinfort.tsvideos.core.external.samba.NasComponent
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.core.external.samba.SmbFileResource
import org.slf4j.Logger
import java.io.BufferedInputStream
import java.io.InputStream

class CreatedFileCommandTest :
    ExpectSpec({
        lateinit var createdFileMapper: CreatedFileMapper
        lateinit var sambaClient: SambaClient
        lateinit var nasComponent: NasComponent
        lateinit var logger: Logger
        lateinit var createdFileCommand: CreatedFileCommand

        val createdFileDto =
            CreatedFileDto(
                id = 1,
                splittedFileId = 2,
                file = "file",
                size = 3,
                mime = "mime",
                encoding = "encoding",
                status = CreatedFileDto.Status.ENCODE_SUCCESS,
            )
        val mp4CreatedFileDto = createdFileDto.copy(mime = "video/mp4")
        val createdFile =
            CreatedFile(
                id = 1,
                splittedFileId = 2,
                file = "file",
                size = 3,
                mime = "mime",
                encoding = "encoding",
                status = CreatedFile.Status.ENCODE_SUCCESS,
            )

        beforeTest {
            clearAllMocks()
            createdFileMapper = mockk()
            sambaClient = mockk()
            nasComponent = mockk()
            logger = mockk()
            createdFileCommand = CreatedFileCommand(createdFileMapper, sambaClient, nasComponent, logger)
        }

        context("selectBySplittedFileId") {
            expect("success") {
                every { createdFileMapper.selectBySplittedFileId(any()) } returns listOf(createdFileDto)

                createdFileCommand.selectBySplittedFileId(2) shouldBe listOf(createdFile)

                verifySequence {
                    createdFileMapper.selectBySplittedFileId(2)
                }
            }
        }

        context("insert") {
            expect("success") {
                every {
                    createdFileMapper.insert(any(), any(), any(), any(), any(), any(), any())
                } answers {
                    (it.invocation.args[6] as GeneratedKeyHolder).id = 1
                    1
                }
                every { logger.info(any()) } just Runs

                val actual = createdFileCommand.insert(2, "file", 3, "mime", "encoding")

                actual shouldBe
                    CreatedFile(
                        id = 1,
                        splittedFileId = 2,
                        file = "file",
                        size = 3,
                        mime = "mime",
                        encoding = "encoding",
                        status = CreatedFile.Status.FILE_MOVED,
                    )
                verify {
                    createdFileMapper.insert(2, "file", 3, "mime", "encoding", "FILE_MOVED", any())
                }
            }

            expect("explicit status") {
                every {
                    createdFileMapper.insert(any(), any(), any(), any(), any(), any(), any())
                } answers {
                    (it.invocation.args[6] as GeneratedKeyHolder).id = 1
                    1
                }
                every { logger.info(any()) } just Runs

                val actual =
                    createdFileCommand.insert(2, "file", 3, "mime", "encoding", CreatedFile.Status.ENCODE_SUCCESS)

                actual.status shouldBe CreatedFile.Status.ENCODE_SUCCESS
                verify {
                    createdFileMapper.insert(2, "file", 3, "mime", "encoding", "ENCODE_SUCCESS", any())
                }
            }

            expect("dryRun") {
                every { logger.info(any()) } just Runs

                val actual = createdFileCommand.insert(2, "file", 3, "mime", "encoding", dryRun = true)

                actual shouldBe
                    CreatedFile(
                        id = 0,
                        splittedFileId = 2,
                        file = "file",
                        size = 3,
                        mime = "mime",
                        encoding = "encoding",
                        status = CreatedFile.Status.FILE_MOVED,
                    )
                verify(exactly = 0) { createdFileMapper.insert(any(), any(), any(), any(), any(), any(), any()) }
            }
        }

        context("updateStatus") {
            expect("success") {
                every { createdFileMapper.updateStatus(any(), any()) } returns 1
                every { logger.info(any()) } just Runs

                val actual = createdFileCommand.updateStatus(createdFile, CreatedFile.Status.FILE_MOVED)

                actual shouldBe createdFile.copy(status = CreatedFile.Status.FILE_MOVED)
                verify { createdFileMapper.updateStatus(createdFile.id, "FILE_MOVED") }
            }

            expect("dryRun") {
                every { logger.info(any()) } just Runs

                val actual = createdFileCommand.updateStatus(createdFile, CreatedFile.Status.FILE_MOVED, true)

                actual shouldBe createdFile.copy(status = CreatedFile.Status.FILE_MOVED)
                verify(exactly = 0) { createdFileMapper.updateStatus(any(), any()) }
            }
        }

        context("findMp4File") {
            expect("success") {
                every { createdFileMapper.find(any()) } returns mp4CreatedFileDto

                createdFileCommand.findMp4File(1) shouldBe createdFile.copy(mime = "video/mp4")

                verifySequence {
                    createdFileMapper.find(1)
                }
            }

            expect("notVideo") {
                every { createdFileMapper.find(any()) } returns createdFileDto

                createdFileCommand.findMp4File(1) shouldBe null

                verifySequence {
                    createdFileMapper.find(1)
                }
            }

            expect("noHit") {
                every { createdFileMapper.find(any()) } returns null

                createdFileCommand.findMp4File(1) shouldBe null

                verifySequence {
                    createdFileMapper.find(1)
                }
            }
        }

        context("streamCreatedFile") {
            expect("success") {
                val testStream = InputStream.nullInputStream()
                val smbResource = mockk<SmbResource>()
                every { createdFileMapper.find(any()) } returns mp4CreatedFileDto
                every { sambaClient.videoStoreNas().resolve(any()) } returns smbResource
                every { smbResource.length() } returns 12345L
                every { smbResource.openInputStream() } returns testStream

                val result = createdFileCommand.streamCreatedFile(1)

                result.shouldBeInstanceOf<SmbFileResource>()
                result?.contentLength() shouldBe 12345L
                result?.inputStream.shouldBeInstanceOf<BufferedInputStream>()

                verifySequence {
                    createdFileMapper.find(1)
                    sambaClient.videoStoreNas().resolve("file")
                    smbResource.length()
                    smbResource.openInputStream()
                }
            }

            expect("successBackSlash") {
                val testStream = InputStream.nullInputStream()
                val smbResource = mockk<SmbResource>()
                every { createdFileMapper.find(any()) } returns mp4CreatedFileDto.copy(file = "test\\")
                every { sambaClient.videoStoreNas().resolve(any()) } returns smbResource
                every { smbResource.length() } returns 12345L
                every { smbResource.openInputStream() } returns testStream

                val result = createdFileCommand.streamCreatedFile(1)

                result.shouldBeInstanceOf<SmbFileResource>()
                result?.contentLength() shouldBe 12345L
                result?.inputStream.shouldBeInstanceOf<BufferedInputStream>()

                verifySequence {
                    createdFileMapper.find(1)
                    sambaClient.videoStoreNas().resolve("test/")
                    smbResource.length()
                    smbResource.openInputStream()
                }
            }

            expect("noFile") {
                val smbResource = mockk<SmbResource>()
                every { createdFileMapper.find(any()) } returns mp4CreatedFileDto
                every { sambaClient.videoStoreNas().resolve(any()) } returns smbResource
                every { smbResource.length() } throws SmbException("err")

                createdFileCommand.streamCreatedFile(1) shouldBe null
            }
            expect("noHit") {
                every { createdFileMapper.find(any()) } returns null

                createdFileCommand.streamCreatedFile(1) shouldBe null

                verifySequence {
                    createdFileMapper.find(1)
                }
            }
        }

        context("delete") {
            expect("success") {
                every { createdFileMapper.delete(any()) } just Runs
                every { nasComponent.deleteResource(createdFile.file) } returns SambaClient.NasType.ORIGINAL_STORE_NAS
                every { logger.info(any()) } just Runs

                createdFileCommand.delete(createdFile) shouldBe SambaClient.NasType.ORIGINAL_STORE_NAS

                verifySequence {
                    createdFileMapper.delete(1)
                    nasComponent.deleteResource(createdFile.file)
                    logger.info(any())
                }
            }

            expect("noHit") {
                every { createdFileMapper.delete(any()) } just Runs
                every { nasComponent.deleteResource(any()) } throws Exception("err")
                every { logger.error(any(), any<Exception>()) } just Runs

                shouldThrow<RuntimeException> {
                    createdFileCommand.delete(createdFile)
                }.message shouldBe "java.lang.Exception: err"

                verifySequence {
                    createdFileMapper.delete(1)
                    nasComponent.deleteResource(createdFile.file)
                    logger.error("Failed to delete file. id=1, file=${createdFile.file}, createdFile=$createdFile", any<Exception>())
                }
            }

            expect("dryRun") {
                every { logger.info(any()) } just Runs

                createdFileCommand.delete(createdFile, true) shouldBe SambaClient.NasType.VIDEO_STORE_NAS

                verifySequence {
                    logger.info(any())
                }
                verify(exactly = 0) {
                    createdFileMapper.delete(any())
                    nasComponent.deleteResource(any())
                }
            }
        }

        context("restoreMoved") {
            expect("moves files back newest first and keeps going after a failure") {
                every { nasComponent.moveResource("new2", "old2") } throws IllegalStateException("busy")
                every { nasComponent.moveResource("new1", "old1") } returns SambaClient.NasType.VIDEO_STORE_NAS
                every { logger.info(any()) } just Runs
                every { logger.error(any(), any<Exception>()) } just Runs

                createdFileCommand.restoreMoved(listOf("old1" to "new1", "old2" to "new2"))

                verifySequence {
                    nasComponent.moveResource("new2", "old2")
                    logger.error(any(), any<IllegalStateException>())
                    nasComponent.moveResource("new1", "old1")
                    logger.info(any())
                }
            }
        }

        context("move") {
            expect("success") {
                every { createdFileMapper.updateFile(any(), any()) } returns 1
                every { nasComponent.moveResource(createdFile.file, "newFile") } returns SambaClient.NasType.ORIGINAL_STORE_NAS
                every { logger.info(any()) } just Runs

                createdFileCommand.move(createdFile, "newFile") shouldBe SambaClient.NasType.ORIGINAL_STORE_NAS

                verifySequence {
                    createdFileMapper.updateFile(1, "newFile")
                    nasComponent.moveResource(createdFile.file, "newFile")
                    logger.info(any())
                }
            }

            expect("noHit") {
                every { createdFileMapper.updateFile(any(), any()) } returns 1
                every { nasComponent.moveResource(any(), any()) } throws Exception("err")
                every { logger.error(any(), any<Exception>()) } just Runs

                shouldThrow<RuntimeException> {
                    createdFileCommand.move(createdFile, "newFile")
                }.message shouldBe "java.lang.Exception: err"

                verifySequence {
                    createdFileMapper.updateFile(1, "newFile")
                    nasComponent.moveResource(createdFile.file, "newFile")
                    logger.error(
                        "Failed to move file. id=1, file=${createdFile.file}, newFile=newFile, createdFile=$createdFile",
                        any<Exception>(),
                    )
                }
            }

            expect("dryRun") {
                every { logger.info(any()) } just Runs

                createdFileCommand.move(createdFile, "newFile", true) shouldBe SambaClient.NasType.VIDEO_STORE_NAS

                verifySequence {
                    logger.info(any())
                }
                verify(exactly = 0) {
                    createdFileMapper.updateFile(any(), any())
                    nasComponent.moveResource(any(), any())
                }
            }
        }
    })
