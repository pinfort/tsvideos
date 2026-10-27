package me.pinfort.tsvideos.catalog.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.persistence.BulkLibraryMapper
import me.pinfort.tsvideos.catalog.persistence.CreatedFileDto
import me.pinfort.tsvideos.catalog.persistence.ProgramDto
import me.pinfort.tsvideos.catalog.persistence.ProgramMapper
import java.time.LocalDateTime
import java.util.HexFormat

/**
 * The statement order inside each lock (backend/docs/module-boundaries.md §7.3). The MANDATORY propagation and the
 * lock retention are covered against the database by CatalogTransactionTest; library's BulkLibraryCommandTest covers the
 * order in which library calls these operations.
 */
class LedgerLocksTest :
    ExpectSpec({
        val mapper = mockk<BulkLibraryMapper>(relaxed = true)
        val programs = mockk<ProgramMapper>()
        val locks = LedgerLocks(mapper, programs)
        val program =
            ProgramDto(1, "first", 10, ProgramDto.Status.COMPLETED, 0, 12, LocalDateTime.of(2026, 1, 1, 0, 0), "1", "t", "c", 12.0)
        val file = CreatedFileDto(30, 20, "base/a/alpha/video.mp4", 12, "video/mp4", null, CreatedFileDto.Status.FILE_MOVED, 1920, 1080)

        beforeTest {
            clearMocks(mapper, programs)
            every { mapper.lockProgramRecording(1) } returns 10
            every { programs.find(1) } returns program
        }

        expect("lockCreatedFiles returns the locked rows as stored") {
            every { mapper.lockFiles() } returns listOf(file)
            locks.lockCreatedFiles() shouldBe
                listOf(
                    CreatedFileSnapshot(
                        30,
                        20,
                        "base/a/alpha/video.mp4",
                        12,
                        "video/mp4",
                        null,
                        CreatedFile.Status.FILE_MOVED,
                        1920,
                        1080,
                    ),
                )
        }

        expect("lockCreatedFilePaths claims one guard per normalized path and per split, all in one ascending key order") {
            val claimed = mutableListOf<String>()
            every { mapper.lockPathGuard(any()) } answers {
                claimed += HexFormat.of().formatHex(firstArg<ByteArray>())
                1
            }
            val paths = listOf("base/b/beta/video.mp4", "BASE\\B\\BETA\\VIDEO.MP4", "base/a/alpha/video.mp4", "base/a/alpha/video.mp4. ")
            locks.lockCreatedFilePaths(paths, listOf(20, 21, 20))
            val expected =
                listOf("base/a/alpha/video.mp4", "base/b/beta/video.mp4").map { CreatedFilePathKey.hash(it) } +
                    listOf(20L, 21L).map { CreatedFilePathKey.splitGuard(it) }
            claimed shouldBe expected.map { HexFormat.of().formatHex(it) }.sorted()
            // A split key never equals a path key, not even of a path spelling the same text.
            (CreatedFilePathKey.splitGuard(20).toList() == CreatedFilePathKey.hash("20").toList()) shouldBe false
        }

        expect("lockCreatedFileRows locks distinct ids in ascending order and skips the query when there are none") {
            every { mapper.lockFileRows(listOf(3, 7)) } returns listOf(3)
            locks.lockCreatedFileRows(listOf(7, 3, 7)) shouldBe listOf(3L)
            locks.lockCreatedFileRows(emptyList()) shouldBe emptyList()
            verify(exactly = 1) { mapper.lockFileRows(any()) }
        }

        expect("lockProgramGraph locks the program, its recording, splits and referencing programs, and only then reads it") {
            locks.lockProgramGraph(1) shouldBe program.toDomain()
            // The plain read comes last, so its snapshot cannot miss a registration that committed while a lock waited.
            verifyOrder {
                mapper.lockProgramRecording(1)
                mapper.lockExecuted(10)
                mapper.lockSplits(10)
                mapper.lockReferences(10)
                programs.find(1)
            }
            verify(exactly = 0) {
                mapper.lockAllPrograms()
                mapper.lockAllExecuted()
                mapper.lockAllSplits()
            }
        }

        expect("lockProgramGraph stops after the program lookup when the program is gone") {
            every { mapper.lockProgramRecording(2) } returns null
            locks.lockProgramGraph(2) shouldBe null
            verify(exactly = 0) {
                mapper.lockExecuted(any())
                mapper.lockSplits(any())
                mapper.lockReferences(any())
                programs.find(any())
            }
        }

        expect("lockWholeLedger locks programs, then recordings, then splits") {
            locks.lockWholeLedger()
            verifyOrder {
                mapper.lockAllPrograms()
                mapper.lockAllExecuted()
                mapper.lockAllSplits()
            }
        }

        expect("lockProgramRow reports whether the program exists") {
            every { mapper.lockProgramRow(1) } returns 1
            every { mapper.lockProgramRow(2) } returns null
            locks.lockProgramRow(1) shouldBe true
            locks.lockProgramRow(2) shouldBe false
        }
    })
