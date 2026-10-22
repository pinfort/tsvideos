package me.pinfort.tsvideos.catalog.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.catalog.persistence.GeneratedKeyHolder
import me.pinfort.tsvideos.catalog.persistence.SplittedFileDto
import me.pinfort.tsvideos.catalog.persistence.SplittedFileMapper
import org.slf4j.Logger

class SplittedFileCommandTest :
    ExpectSpec({
        lateinit var splittedFileMapper: SplittedFileMapper
        lateinit var logger: Logger
        lateinit var splittedFileCommand: SplittedFileCommand

        val splittedFileDto =
            SplittedFileDto(
                id = 1,
                executedFileId = 1,
                file = "test.ts",
                size = 1,
                duration = 1.0,
                status = SplittedFileDto.Status.REGISTERED,
            )

        val splittedFile =
            SplittedFile(
                id = 1,
                executedFileId = 1,
                file = "test.ts",
                size = 1,
                duration = 1.0,
                status = SplittedFile.Status.REGISTERED,
            )

        beforeTest {
            clearAllMocks()
            splittedFileMapper = mockk()
            logger = mockk()
            splittedFileCommand = SplittedFileCommand(splittedFileMapper, logger)
        }

        context("selectByExecutedFileId") {
            expect("success") {
                every { splittedFileMapper.selectByExecutedFileId(any()) } returns listOf(splittedFileDto)

                splittedFileCommand.selectByExecutedFileId(1) shouldBe listOf(splittedFile)

                verifySequence {
                    splittedFileMapper.selectByExecutedFileId(1)
                }
            }
        }

        context("findByFile") {
            expect("success") {
                every { splittedFileMapper.selectByFile("test.ts") } returns listOf(splittedFileDto)

                splittedFileCommand.findByFile("test.ts") shouldBe splittedFile

                verifySequence {
                    splittedFileMapper.selectByFile("test.ts")
                }
            }

            expect("null when not found") {
                every { splittedFileMapper.selectByFile("test.ts") } returns emptyList()

                splittedFileCommand.findByFile("test.ts") shouldBe null

                verifySequence {
                    splittedFileMapper.selectByFile("test.ts")
                }
            }
        }

        context("insert") {
            expect("success") {
                every {
                    splittedFileMapper.insert(any(), any(), any(), any(), any(), any())
                } answers {
                    (it.invocation.args[5] as GeneratedKeyHolder).id = 1
                    1
                }
                every { logger.info(any()) } just Runs

                val actual = splittedFileCommand.insert(1, "test.ts", 1, 1.0)

                actual shouldBe splittedFile
                verify {
                    splittedFileMapper.insert(1, "test.ts", 1, 1.0, "REGISTERED", any())
                }
            }

            expect("dryRun") {
                every { logger.info(any()) } just Runs

                val actual = splittedFileCommand.insert(1, "test.ts", 1, 1.0, true)

                actual shouldBe splittedFile.copy(id = 0)
                verify(exactly = 0) { splittedFileMapper.insert(any(), any(), any(), any(), any(), any()) }
            }
        }

        context("updateStatus") {
            expect("success") {
                every { splittedFileMapper.updateStatus(any(), any()) } returns 1
                every { logger.info(any()) } just Runs

                val actual = splittedFileCommand.updateStatus(splittedFile, SplittedFile.Status.COMPRESS_SAVED)

                actual shouldBe splittedFile.copy(status = SplittedFile.Status.COMPRESS_SAVED)
                verifySequence {
                    splittedFileMapper.updateStatus(1, "COMPRESS_SAVED")
                    logger.info(any())
                }
            }

            expect("dryRun") {
                every { logger.info(any()) } just Runs

                val actual = splittedFileCommand.updateStatus(splittedFile, SplittedFile.Status.COMPRESS_SAVED, true)

                actual shouldBe splittedFile.copy(status = SplittedFile.Status.COMPRESS_SAVED)
                verify(exactly = 0) { splittedFileMapper.updateStatus(any(), any()) }
            }
        }

        context("delete") {
            expect("success") {
                every { splittedFileMapper.delete(any()) } just Runs
                every { logger.info(any()) } just Runs

                splittedFileCommand.delete(splittedFile)

                verifySequence {
                    splittedFileMapper.delete(splittedFile.id)
                    logger.info(
                        "Delete splitted file, id=1, splittedFile=SplittedFile(id=1, executedFileId=1, file=test.ts, size=1, duration=1.0, status=REGISTERED)",
                    )
                }
            }

            expect("dryRun") {
                every { logger.info(any()) } just Runs

                splittedFileCommand.delete(splittedFile, true)

                verifySequence {
                    logger.info(
                        "Delete splitted file, id=1, splittedFile=SplittedFile(id=1, executedFileId=1, file=test.ts, size=1, duration=1.0, status=REGISTERED)",
                    )
                }
                verify(exactly = 0) { splittedFileMapper.delete(any()) }
            }
        }
    })
