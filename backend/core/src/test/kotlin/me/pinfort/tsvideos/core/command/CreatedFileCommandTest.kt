package me.pinfort.tsvideos.core.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.external.database.dto.CreatedFileDto
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.storage.api.NasComponent
import me.pinfort.tsvideos.storage.api.NasStore
import org.slf4j.Logger

class CreatedFileCommandTest :
    ExpectSpec({
        lateinit var createdFileMapper: CreatedFileMapper
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
            nasComponent = mockk()
            logger = mockk()
            createdFileCommand = CreatedFileCommand(createdFileMapper, nasComponent, logger)
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

        context("delete") {
            expect("success") {
                every { createdFileMapper.delete(any()) } just Runs
                every { nasComponent.deleteResource(createdFile.file) } returns NasStore.ORIGINAL_STORE_NAS
                every { logger.info(any()) } just Runs

                createdFileCommand.delete(createdFile) shouldBe NasStore.ORIGINAL_STORE_NAS

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

                createdFileCommand.delete(createdFile, true) shouldBe NasStore.VIDEO_STORE_NAS

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
                every { nasComponent.moveResource("new1", "old1") } returns NasStore.VIDEO_STORE_NAS
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
                every { nasComponent.moveResource(createdFile.file, "newFile") } returns NasStore.ORIGINAL_STORE_NAS
                every { logger.info(any()) } just Runs

                createdFileCommand.move(createdFile, "newFile") shouldBe NasStore.ORIGINAL_STORE_NAS

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

                createdFileCommand.move(createdFile, "newFile", true) shouldBe NasStore.VIDEO_STORE_NAS

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
