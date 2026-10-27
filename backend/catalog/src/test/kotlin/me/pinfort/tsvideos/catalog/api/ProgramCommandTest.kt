package me.pinfort.tsvideos.catalog.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import me.pinfort.tsvideos.catalog.domain.ExecutedFileCheck
import me.pinfort.tsvideos.catalog.domain.ExecutedFileTag
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.ProgramDetail
import me.pinfort.tsvideos.catalog.domain.ProgramSearchPage
import me.pinfort.tsvideos.catalog.domain.ProgramSearchQuery
import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.catalog.persistence.CreatedFileDto
import me.pinfort.tsvideos.catalog.persistence.CreatedFileMapper
import me.pinfort.tsvideos.catalog.persistence.GeneratedKeyHolder
import me.pinfort.tsvideos.catalog.persistence.ProgramDto
import me.pinfort.tsvideos.catalog.persistence.ProgramMapper
import me.pinfort.tsvideos.catalog.persistence.SplittedFileDto
import me.pinfort.tsvideos.catalog.persistence.SplittedFileMapper
import me.pinfort.tsvideos.storage.api.NasStore
import org.slf4j.Logger
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import java.nio.file.Path
import java.time.LocalDateTime

class ProgramCommandTest :
    ExpectSpec({

        lateinit var programMapper: ProgramMapper
        lateinit var createdFileMapper: CreatedFileMapper
        lateinit var executedFileCommand: ExecutedFileCommand
        lateinit var createdFileCommand: CreatedFileCommand
        lateinit var splittedFileMapper: SplittedFileMapper
        lateinit var logger: Logger
        lateinit var splittedFileCommand: SplittedFileCommand
        lateinit var directoryNameComponent: DirectoryNameComponent
        lateinit var executedFileTagCommand: ExecutedFileTagCommand
        lateinit var ledgerLocks: LedgerLocks
        lateinit var transactionManager: PlatformTransactionManager
        lateinit var programCommand: ProgramCommand

        beforeTest {
            clearAllMocks()

            programMapper = mockk<ProgramMapper>()
            createdFileMapper = mockk<CreatedFileMapper>()
            executedFileCommand = mockk<ExecutedFileCommand>()
            createdFileCommand = mockk<CreatedFileCommand>()
            splittedFileMapper = mockk<SplittedFileMapper>()
            logger = mockk<Logger>()
            splittedFileCommand = mockk<SplittedFileCommand>()
            directoryNameComponent = mockk<DirectoryNameComponent>()
            executedFileTagCommand = mockk<ExecutedFileTagCommand>()
            ledgerLocks = mockk<LedgerLocks>()
            transactionManager = mockk<PlatformTransactionManager>()
            every { transactionManager.getTransaction(any()) } returns SimpleTransactionStatus()
            every { transactionManager.commit(any()) } just Runs
            every { transactionManager.rollback(any()) } just Runs
            programCommand =
                ProgramCommand(
                    programMapper,
                    createdFileMapper,
                    executedFileCommand,
                    createdFileCommand,
                    splittedFileMapper,
                    logger,
                    splittedFileCommand,
                    directoryNameComponent,
                    executedFileTagCommand,
                    ledgerLocks,
                    transactionManager,
                )
        }

        val program =
            Program(
                id = 1,
                name = "name",
                executedFileId = 2,
                status = Program.Status.COMPLETED,
                drops = 3,
                size = 4,
                recordedAt = LocalDateTime.of(2020, 1, 1, 0, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 5.0,
            )
        val programDto =
            ProgramDto(
                id = 1,
                name = "name",
                executedFileId = 2,
                status = ProgramDto.Status.COMPLETED,
                drops = 3,
                size = 4,
                recordedAt = LocalDateTime.of(2020, 1, 1, 0, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 5.0,
            )
        val createdFileDto =
            CreatedFileDto(
                id = 1,
                splittedFileId = 2,
                file = "file\\file1",
                size = 3,
                mime = "mime",
                encoding = "encoding",
                status = CreatedFileDto.Status.ENCODE_SUCCESS,
            )
        val tsCreatedFileDto = createdFileDto.copy(mime = "video/vnd.dlna.mpeg-tts")
        val createdFile =
            CreatedFile(
                id = 1,
                splittedFileId = 2,
                file = "file\\file1",
                size = 3,
                mime = "mime",
                encoding = "encoding",
                status = CreatedFile.Status.ENCODE_SUCCESS,
            )
        val programDetail =
            ProgramDetail(
                id = 1,
                name = "name",
                executedFileId = 2,
                status = Program.Status.COMPLETED,
                drops = 3,
                size = 4,
                recordedAt = LocalDateTime.of(2020, 1, 1, 0, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 5.0,
                createdFiles = listOf(createdFile),
            )
        val executedFile =
            ExecutedFile(
                id = 1,
                status = ExecutedFile.Status.REGISTERED,
                size = 2,
                recordedAt = LocalDateTime.of(2020, 1, 1, 0, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 3.0,
                drops = 4,
                file = "file",
            )
        val splittedFileDto =
            SplittedFileDto(
                id = 1,
                executedFileId = 2,
                file = "file",
                size = 3,
                status = SplittedFileDto.Status.REGISTERED,
                duration = 4.0,
            )
        val splittedFile =
            SplittedFile(
                id = 1,
                executedFileId = 2,
                file = "file",
                size = 3,
                status = SplittedFile.Status.REGISTERED,
                duration = 4.0,
            )

        context("findByName") {
            expect("success") {
                every { programMapper.findByName(any()) } returns programDto

                val actual = programCommand.findByName("name")

                actual shouldBe program

                verifySequence {
                    programMapper.findByName("name")
                }
            }

            expect("noHit") {
                every { programMapper.findByName(any()) } returns null

                val actual = programCommand.findByName("name")

                actual shouldBe null

                verifySequence {
                    programMapper.findByName("name")
                }
            }
        }

        context("findByExecutedFileId") {
            expect("success") {
                every { programMapper.findByExecutedFileId(any()) } returns programDto

                programCommand.findByExecutedFileId(2) shouldBe program

                verifySequence {
                    programMapper.findByExecutedFileId(2)
                }
            }

            expect("noHit") {
                every { programMapper.findByExecutedFileId(any()) } returns null

                programCommand.findByExecutedFileId(2) shouldBe null
            }
        }

        context("updateStatusByExecutedFileId") {
            expect("success") {
                every { programMapper.updateStatusByExecutedFileId(any(), any()) } returns 1
                every { logger.info(any()) } just Runs

                programCommand.updateStatusByExecutedFileId(2, Program.Status.COMPLETED)

                verifySequence {
                    programMapper.updateStatusByExecutedFileId(2, "COMPLETED")
                    logger.info(any())
                }
            }

            expect("dryRun") {
                every { logger.info(any()) } just Runs

                programCommand.updateStatusByExecutedFileId(2, Program.Status.ERROR, true)

                verify(exactly = 0) { programMapper.updateStatusByExecutedFileId(any(), any()) }
            }
        }

        context("insert") {
            expect("success") {
                every {
                    programMapper.insert(any(), any(), any(), any())
                } answers {
                    (it.invocation.args[3] as GeneratedKeyHolder).id = 1
                    1
                }
                every { programMapper.find(any()) } returns programDto
                every { logger.info(any()) } just Runs

                val actual = programCommand.insert("name", 2)

                actual shouldBe program
                verify {
                    programMapper.insert("name", 2, "REGISTERED", any())
                    programMapper.find(1)
                }
            }

            expect("dryRun") {
                every { logger.info(any()) } just Runs

                val actual = programCommand.insert("name", 2, true)

                actual.name shouldBe "name"
                actual.executedFileId shouldBe 2
                actual.status shouldBe Program.Status.REGISTERED
                verify(exactly = 0) { programMapper.insert(any(), any(), any(), any()) }
            }
        }

        context("deleteByExecutedFileId") {
            expect("success") {
                every { programMapper.deleteByExecutedFileId(any()) } returns 1
                every { logger.info(any()) } just Runs

                programCommand.deleteByExecutedFileId(2)

                verifySequence {
                    programMapper.deleteByExecutedFileId(2)
                    logger.info(any())
                }
            }

            expect("dryRun") {
                every { logger.info(any()) } just Runs

                programCommand.deleteByExecutedFileId(2, true)

                verify(exactly = 0) { programMapper.deleteByExecutedFileId(any()) }
            }
        }

        context("selectByName") {
            expect("success") {
                every { programMapper.selectByName(any(), any(), any()) } returns listOf(programDto)

                val actual = programCommand.selectByName("test", 1, 2)

                actual shouldBe listOf(program)

                verifySequence {
                    programMapper.selectByName("test", 1, 2)
                }
            }

            expect("noHit") {
                every { programMapper.selectByName(any(), any(), any()) } returns emptyList()

                val actual = programCommand.selectByName("test", 1, 2)

                actual shouldBe emptyList()

                verifySequence {
                    programMapper.selectByName("test", 1, 2)
                }
            }
        }

        context("search") {
            expect("combines the page and the total of the same query") {
                val query = ProgramSearchQuery(name = "test", limit = 10, offset = 20)
                every { programMapper.search(query) } returns listOf(programDto)
                every { programMapper.countSearch(query) } returns 42

                val actual = programCommand.search(query)

                actual shouldBe ProgramSearchPage(listOf(program), 42, 10, 20)

                verifySequence {
                    programMapper.search(query)
                    programMapper.countSearch(query)
                }
            }
        }

        context("find") {
            expect("success") {
                every { programMapper.find(any()) } returns programDto

                val actual = programCommand.find(1)

                actual shouldBe program

                verifySequence {
                    programMapper.find(1)
                }
            }

            expect("noHit") {
                every { programMapper.find(any()) } returns null

                val actual = programCommand.find(1)

                actual shouldBe null

                verifySequence {
                    programMapper.find(1)
                }
            }
        }

        context("videoFiles") {
            expect("success") {
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto)

                val testProgram = program.copy(executedFileId = 1)

                val actual = programCommand.videoFiles(testProgram)

                actual shouldBe listOf(createdFile)

                verifySequence {
                    createdFileMapper.selectByExecutedFileId(1)
                }
            }

            expect("noHit") {
                every { createdFileMapper.selectByExecutedFileId(any()) } returns emptyList()

                val testProgram = program.copy(executedFileId = 1)

                val actual = programCommand.videoFiles(testProgram)

                actual shouldBe emptyList()

                verifySequence {
                    createdFileMapper.selectByExecutedFileId(1)
                }
            }
        }

        context("hasTsFile") {
            expect("success") {
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(tsCreatedFileDto)

                val actual = programCommand.hasTsFile(program)

                actual shouldBe true
            }

            expect("successNoTs") {
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto)

                val actual = programCommand.hasTsFile(program)

                actual shouldBe false
            }

            expect("successNoResult") {
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf()

                val actual = programCommand.hasTsFile(program)

                actual shouldBe false
            }

            // Records which elements were read so the tests can tell where the scan stopped.
            class ReadTrackingList(
                private val elements: List<CreatedFileDto>,
            ) : AbstractList<CreatedFileDto>() {
                val readIndices = mutableListOf<Int>()
                override val size: Int get() = elements.size

                override fun get(index: Int): CreatedFileDto {
                    readIndices += index
                    return elements[index]
                }
            }

            expect("a TS file first stops the scan without reading the rest") {
                val files = ReadTrackingList(listOf(tsCreatedFileDto, createdFileDto, createdFileDto.copy(mime = null)))
                every { createdFileMapper.selectByExecutedFileId(any()) } returns files

                programCommand.hasTsFile(program) shouldBe true

                files.readIndices shouldBe listOf(0)
                verify(exactly = 1) { createdFileMapper.selectByExecutedFileId(program.executedFileId) }
            }

            expect("a TS file in the middle is found after the earlier files and stops the scan") {
                val files = ReadTrackingList(listOf(createdFileDto, tsCreatedFileDto, createdFileDto.copy(id = 3)))
                every { createdFileMapper.selectByExecutedFileId(any()) } returns files

                programCommand.hasTsFile(program) shouldBe true

                files.readIndices shouldBe listOf(0, 1)
                verify(exactly = 1) { createdFileMapper.selectByExecutedFileId(program.executedFileId) }
            }

            expect("files with a null MIME are not TS and are all checked") {
                val files = ReadTrackingList(listOf(createdFileDto.copy(mime = null), createdFileDto.copy(id = 3, mime = null)))
                every { createdFileMapper.selectByExecutedFileId(any()) } returns files

                programCommand.hasTsFile(program) shouldBe false

                files.readIndices shouldBe listOf(0, 1)
                verify(exactly = 1) { createdFileMapper.selectByExecutedFileId(program.executedFileId) }
            }
        }

        context("findDetail") {
            expect("success") {
                every { programMapper.find(any()) } returns programDto
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto)
                every { executedFileTagCommand.selectTags(any()) } returns listOf(ExecutedFileTag.EWS)
                every { executedFileTagCommand.selectChecks(any()) } returns listOf(ExecutedFileCheck.EMERGENCY_BROADCAST)

                val actual = programCommand.findDetail(1)

                actual shouldBe
                    programDetail.copy(
                        tags = listOf(ExecutedFileTag.EWS),
                        checks = listOf(ExecutedFileCheck.EMERGENCY_BROADCAST),
                    )

                verifySequence {
                    programMapper.find(1)
                    createdFileMapper.selectByExecutedFileId(programDto.executedFileId)
                    executedFileTagCommand.selectTags(programDto.executedFileId)
                    executedFileTagCommand.selectChecks(programDto.executedFileId)
                }
            }

            expect("noHit") {
                every { programMapper.find(any()) } returns null

                val actual = programCommand.findDetail(1)

                actual shouldBe null

                verifySequence {
                    programMapper.find(1)
                }
            }
        }

        context("delete") {
            expect("success") {
                every { executedFileCommand.find(any()) } returns executedFile
                every { splittedFileMapper.selectByExecutedFileId(any()) } returns listOf(splittedFileDto)
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto)
                every { splittedFileCommand.delete(any()) } just Runs
                every { logger.info(any()) } just Runs
                every { createdFileCommand.delete(any()) } returns NasStore.ORIGINAL_STORE_NAS
                every { executedFileCommand.delete(any()) } just Runs
                every { programMapper.deleteById(any()) } just Runs

                programCommand.delete(program)

                verifySequence {
                    executedFileCommand.find(program.executedFileId)
                    splittedFileMapper.selectByExecutedFileId(executedFile.id)
                    createdFileMapper.selectByExecutedFileId(program.executedFileId)
                    splittedFileCommand.delete(splittedFile, false)
                    createdFileCommand.delete(createdFile, false)
                    executedFileCommand.delete(executedFile, false)
                    programMapper.deleteById(program.id)
                    logger.info(any())
                }
            }

            expect("executedNotFound") {
                every { executedFileCommand.find(any()) } throws Exception("not found")

                val ex =
                    shouldThrow<Exception> {
                        programCommand.delete(program)
                    }
                ex.message shouldBe "not found"

                verifySequence {
                    executedFileCommand.find(program.executedFileId)
                }
            }

            expect("dryRun") {
                every { executedFileCommand.find(any()) } returns executedFile
                every { splittedFileMapper.selectByExecutedFileId(any()) } returns listOf(splittedFileDto)
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto)
                every { splittedFileCommand.delete(any(), any()) } just Runs
                every { logger.info(any()) } just Runs
                every { createdFileCommand.delete(any(), any()) } returns NasStore.ORIGINAL_STORE_NAS
                every { executedFileCommand.delete(any(), any()) } just Runs
                every { programMapper.deleteById(any()) } just Runs

                programCommand.delete(program, true)

                verifySequence {
                    executedFileCommand.find(program.executedFileId)
                    splittedFileMapper.selectByExecutedFileId(executedFile.id)
                    createdFileMapper.selectByExecutedFileId(program.executedFileId)
                    splittedFileCommand.delete(splittedFile, true)
                    createdFileCommand.delete(createdFile, true)
                    executedFileCommand.delete(executedFile, true)
                    logger.info(any())
                }
                verify(exactly = 0) {
                    programMapper.deleteById(program.id)
                }
            }

            expect("exception") {
                every { executedFileCommand.find(any()) } returns executedFile
                every { splittedFileMapper.selectByExecutedFileId(any()) } returns listOf(splittedFileDto)
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto)
                every { splittedFileCommand.delete(any(), any()) } just Runs
                every { logger.info(any()) } just Runs
                every { createdFileCommand.delete(any(), any()) } throws RuntimeException("error")
                every { executedFileCommand.delete(any(), any()) } just Runs
                every { programMapper.deleteById(any()) } just Runs

                shouldThrow<RuntimeException> {
                    programCommand.delete(program, true)
                }

                verifySequence {
                    executedFileCommand.find(program.executedFileId)
                    splittedFileMapper.selectByExecutedFileId(executedFile.id)
                    createdFileMapper.selectByExecutedFileId(program.executedFileId)
                    splittedFileCommand.delete(splittedFile, true)
                    createdFileCommand.delete(createdFile, true)
                }
                verify(exactly = 0) {
                    programMapper.deleteById(program.id)
                    executedFileCommand.delete(executedFile, true)
                    logger.info(any())
                }
            }
        }

        context("moveCreatedFiles") {
            fun lockAll() {
                every { splittedFileMapper.selectByExecutedFileId(program.executedFileId) } returns listOf(splittedFileDto)
                every { ledgerLocks.lockCreatedFilePaths(any(), any()) } just Runs
                every { ledgerLocks.lockCreatedFileRows(any()) } answers { firstArg<Collection<Long>>().sorted() }
                every { ledgerLocks.lockProgramGraph(program.id) } returns program
            }

            expect("locks the paths, the splits, the rows and the program graph, then re-reads, before any NAS move") {
                lockAll()
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto)
                every { createdFileCommand.moveResource(any(), any()) } returns NasStore.ORIGINAL_STORE_NAS
                every { createdFileCommand.recordMove(any(), any()) } just Runs
                every { logger.info(any()) } just Runs
                every { directoryNameComponent.replaceWithGivenDirectoryName(any(), any()) } returns Path.of("newPath")

                programCommand.moveCreatedFiles(program, "newDirectory")

                verifySequence {
                    createdFileMapper.selectByExecutedFileId(program.executedFileId)
                    splittedFileMapper.selectByExecutedFileId(program.executedFileId)
                    ledgerLocks.lockCreatedFilePaths(listOf("file\\file1", "newPath"), listOf(splittedFileDto.id))
                    ledgerLocks.lockCreatedFileRows(listOf(1L))
                    ledgerLocks.lockProgramGraph(program.id)
                    createdFileMapper.selectByExecutedFileId(program.executedFileId)
                    splittedFileMapper.selectByExecutedFileId(program.executedFileId)
                    createdFileCommand.moveResource(createdFile, "newPath")
                    createdFileCommand.recordMove(createdFile, "newPath")
                    logger.info(any())
                }
                verify(exactly = 1) { transactionManager.commit(any()) }
            }

            expect("a file added or changed before the locks were granted refuses the move before any NAS change") {
                lockAll()
                val added = createdFileDto.copy(id = 2, file = "file\\file2")
                val changed = createdFileDto.copy(file = "x\\y")
                every { createdFileMapper.selectByExecutedFileId(any()) } returnsMany
                    listOf(listOf(createdFileDto), listOf(createdFileDto, added), listOf(createdFileDto), listOf(changed))
                every { directoryNameComponent.replaceWithGivenDirectoryName(any(), any()) } returns Path.of("newPath")

                shouldThrow<IllegalStateException> { programCommand.moveCreatedFiles(program, "newDirectory") }
                shouldThrow<IllegalStateException> { programCommand.moveCreatedFiles(program, "newDirectory") }

                // A split added meanwhile (a registration on it would not be held up by the guards taken).
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto)
                every { splittedFileMapper.selectByExecutedFileId(program.executedFileId) } returnsMany
                    listOf(listOf(splittedFileDto), listOf(splittedFileDto, splittedFileDto.copy(id = 5)))
                shouldThrow<IllegalStateException> { programCommand.moveCreatedFiles(program, "newDirectory") }

                verify(exactly = 0) {
                    createdFileCommand.moveResource(any(), any(), any())
                    createdFileCommand.recordMove(any(), any(), any())
                }
                verify(exactly = 3) { transactionManager.rollback(any()) }
            }

            expect("a program deleted or re-pointed before the locks were granted refuses the move before any NAS change") {
                lockAll()
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto)
                every { directoryNameComponent.replaceWithGivenDirectoryName(any(), any()) } returns Path.of("newPath")
                every { ledgerLocks.lockProgramGraph(program.id) } returnsMany listOf(null, program.copy(executedFileId = 99))

                shouldThrow<IllegalStateException> { programCommand.moveCreatedFiles(program, "newDirectory") }
                shouldThrow<IllegalStateException> { programCommand.moveCreatedFiles(program, "newDirectory") }

                verify(exactly = 0) { createdFileCommand.moveResource(any(), any(), any()) }
            }

            expect("every NAS file is moved before the first row is updated") {
                lockAll()
                val second = createdFileDto.copy(id = 2, file = "file\\file2")
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto, second)
                every { directoryNameComponent.replaceWithGivenDirectoryName(Path.of("file/file1"), any()) } returns Path.of("new/file1")
                every { directoryNameComponent.replaceWithGivenDirectoryName(Path.of("file/file2"), any()) } returns Path.of("new/file2")
                every { createdFileCommand.moveResource(any(), any(), any()) } returns NasStore.VIDEO_STORE_NAS
                every { createdFileCommand.recordMove(any(), any(), any()) } just Runs
                every { logger.info(any()) } just Runs

                programCommand.moveCreatedFiles(program, "newDirectory")

                verifySequence {
                    createdFileMapper.selectByExecutedFileId(program.executedFileId)
                    splittedFileMapper.selectByExecutedFileId(program.executedFileId)
                    ledgerLocks.lockCreatedFilePaths(listOf("file\\file1", "new\\file1", "file\\file2", "new\\file2"), listOf(1L))
                    ledgerLocks.lockCreatedFileRows(listOf(1L, 2L))
                    ledgerLocks.lockProgramGraph(program.id)
                    createdFileMapper.selectByExecutedFileId(program.executedFileId)
                    splittedFileMapper.selectByExecutedFileId(program.executedFileId)
                    createdFileCommand.moveResource(createdFile, "new\\file1", false)
                    createdFileCommand.moveResource(second.toDomain(), "new\\file2", false)
                    createdFileCommand.recordMove(createdFile, "new\\file1", false)
                    createdFileCommand.recordMove(second.toDomain(), "new\\file2", false)
                    logger.info(any())
                }
            }

            expect("a later failure moves earlier files back and rethrows") {
                lockAll()
                val second = createdFileDto.copy(id = 2, file = "file\\file2")
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto, second)
                every { directoryNameComponent.replaceWithGivenDirectoryName(Path.of("file/file1"), any()) } returns Path.of("new/file1")
                every { directoryNameComponent.replaceWithGivenDirectoryName(Path.of("file/file2"), any()) } returns Path.of("new/file2")
                every { createdFileCommand.moveResource(createdFile, "new\\file1") } returns NasStore.VIDEO_STORE_NAS
                every { createdFileCommand.moveResource(second.toDomain(), "new\\file2") } throws RuntimeException("nas")
                every { createdFileCommand.restoreMoved(any()) } just Runs

                shouldThrow<RuntimeException> { programCommand.moveCreatedFiles(program, "newDirectory") }

                verify(exactly = 1) { createdFileCommand.restoreMoved(listOf("file\\file1" to "new\\file1")) }
                verify(exactly = 0) { createdFileCommand.recordMove(any(), any(), any()) }
            }

            expect("a row update failure after the NAS moves moves every file back and rethrows") {
                lockAll()
                val second = createdFileDto.copy(id = 2, file = "file\\file2")
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto, second)
                every { directoryNameComponent.replaceWithGivenDirectoryName(Path.of("file/file1"), any()) } returns Path.of("new/file1")
                every { directoryNameComponent.replaceWithGivenDirectoryName(Path.of("file/file2"), any()) } returns Path.of("new/file2")
                every { createdFileCommand.moveResource(any(), any(), any()) } returns NasStore.VIDEO_STORE_NAS
                every { createdFileCommand.recordMove(createdFile, "new\\file1", false) } just Runs
                every { createdFileCommand.recordMove(second.toDomain(), "new\\file2", false) } throws RuntimeException("db")
                every { createdFileCommand.restoreMoved(any()) } just Runs

                shouldThrow<RuntimeException> { programCommand.moveCreatedFiles(program, "newDirectory") }

                verify(exactly = 1) {
                    createdFileCommand.restoreMoved(listOf("file\\file1" to "new\\file1", "file\\file2" to "new\\file2"))
                }
            }

            expect("dryRun") {
                every { createdFileMapper.selectByExecutedFileId(any()) } returns listOf(createdFileDto)
                every { createdFileCommand.moveResource(any(), any(), any()) } returns NasStore.ORIGINAL_STORE_NAS
                every { createdFileCommand.recordMove(any(), any(), any()) } just Runs
                every { logger.info(any()) } just Runs
                every { directoryNameComponent.replaceWithGivenDirectoryName(any(), any()) } returns Path.of("newPath")

                programCommand.moveCreatedFiles(program, "newDirectory", true)

                verifySequence {
                    createdFileMapper.selectByExecutedFileId(program.executedFileId)
                    createdFileCommand.moveResource(createdFile, "newPath", true)
                    createdFileCommand.recordMove(createdFile, "newPath", true)
                    logger.info(any())
                }
                verify(exactly = 0) { transactionManager.getTransaction(any()) }
                confirmVerified(ledgerLocks)
            }
        }
    })
