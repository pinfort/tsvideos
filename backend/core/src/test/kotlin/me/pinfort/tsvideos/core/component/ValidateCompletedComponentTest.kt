package me.pinfort.tsvideos.core.component

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import me.pinfort.tsvideos.catalog.api.CreatedFileCommand
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.api.SplittedFileCommand
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.storage.api.NasComponent
import org.slf4j.Logger
import java.time.LocalDateTime

class ValidateCompletedComponentTest :
    ExpectSpec({
        lateinit var programCommand: ProgramCommand
        lateinit var splittedFileCommand: SplittedFileCommand
        lateinit var createdFileCommand: CreatedFileCommand
        lateinit var nasComponent: NasComponent
        lateinit var logger: Logger
        lateinit var validateCompletedComponent: ValidateCompletedComponent

        val program =
            Program(
                id = 1,
                name = "program",
                executedFileId = 10,
                status = Program.Status.REGISTERED,
                drops = 0,
                size = 100,
                recordedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 1800.0,
            )

        val splittedFile =
            SplittedFile(
                id = 20,
                executedFileId = 10,
                file = "splitted.m2ts",
                size = 100,
                duration = 1800.0,
                status = SplittedFile.Status.COMPRESS_SAVED,
            )

        fun createdFile(
            id: Long,
            file: String,
            mime: String?,
            encoding: String?,
        ) = CreatedFile(
            id = id,
            splittedFileId = 20,
            file = file,
            size = 100,
            mime = mime,
            encoding = encoding,
            status = CreatedFile.Status.FILE_MOVED,
        )

        val gzipFile = createdFile(30, "g/gzip/splitted.m2ts.gz", "video/vnd.dlna.mpeg-tts", "gzip")
        val movieFile = createdFile(31, "m/movie/encoded.mp4", "video/mp4", null)

        beforeTest {
            clearAllMocks()
            programCommand = mockk()
            splittedFileCommand = mockk()
            createdFileCommand = mockk()
            nasComponent = mockk()
            logger = mockk()
            every { logger.info(any()) } just Runs
            validateCompletedComponent =
                ValidateCompletedComponent(
                    programCommand,
                    splittedFileCommand,
                    createdFileCommand,
                    nasComponent,
                    logger,
                )
        }

        context("validate") {
            expect("true when both the gzipped ts and the movie exist on the nas") {
                every { programCommand.find(1) } returns program
                every { splittedFileCommand.selectByExecutedFileId(10) } returns listOf(splittedFile)
                every { createdFileCommand.selectBySplittedFileId(20) } returns listOf(gzipFile, movieFile)
                every { nasComponent.resourceExists(any()) } returns true

                validateCompletedComponent.validate(1) shouldBe true
            }

            expect("false when program is not found") {
                every { programCommand.find(1) } returns null

                validateCompletedComponent.validate(1) shouldBe false
            }

            expect("false when no splitted file is registered") {
                every { programCommand.find(1) } returns program
                every { splittedFileCommand.selectByExecutedFileId(10) } returns emptyList()

                validateCompletedComponent.validate(1) shouldBe false
            }

            expect("false when only the gzipped ts exists") {
                every { programCommand.find(1) } returns program
                every { splittedFileCommand.selectByExecutedFileId(10) } returns listOf(splittedFile)
                every { createdFileCommand.selectBySplittedFileId(20) } returns listOf(gzipFile)
                every { nasComponent.resourceExists(any()) } returns true

                validateCompletedComponent.validate(1) shouldBe false
            }

            expect("false when only the movie exists") {
                every { programCommand.find(1) } returns program
                every { splittedFileCommand.selectByExecutedFileId(10) } returns listOf(splittedFile)
                every { createdFileCommand.selectBySplittedFileId(20) } returns listOf(movieFile)
                every { nasComponent.resourceExists(any()) } returns true

                validateCompletedComponent.validate(1) shouldBe false
            }

            expect("false when a registered file is missing from the nas") {
                every { programCommand.find(1) } returns program
                every { splittedFileCommand.selectByExecutedFileId(10) } returns listOf(splittedFile)
                every { createdFileCommand.selectBySplittedFileId(20) } returns listOf(gzipFile, movieFile)
                every { nasComponent.resourceExists(gzipFile.file) } returns true
                every { nasComponent.resourceExists(movieFile.file) } returns false

                validateCompletedComponent.validate(1) shouldBe false
            }

            expect("false when the ts file is not gzipped") {
                every { programCommand.find(1) } returns program
                every { splittedFileCommand.selectByExecutedFileId(10) } returns listOf(splittedFile)
                every { createdFileCommand.selectBySplittedFileId(20) } returns
                    listOf(createdFile(30, "g/gzip/splitted.m2ts", "video/vnd.dlna.mpeg-tts", null), movieFile)
                every { nasComponent.resourceExists(any()) } returns true

                validateCompletedComponent.validate(1) shouldBe false
            }
        }
    })
