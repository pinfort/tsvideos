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

/**
 * The statement order inside each lock (backend/docs/module-boundaries.md §7.3). The MANDATORY propagation and the
 * lock retention are covered against the database by CatalogTransactionTest; core's BulkLibraryCommandTest covers the
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
            every { mapper.lockProgram(any()) } answers { firstArg() }
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

        expect("lockProgramGraph locks the program, reads it, then its recording, splits and referencing programs") {
            locks.lockProgramGraph(1) shouldBe program.toDomain()
            verifyOrder {
                mapper.lockProgram(1)
                programs.find(1)
                mapper.lockExecuted(10)
                mapper.lockSplits(10)
                mapper.lockReferences(10)
            }
            verify(exactly = 0) {
                mapper.lockAllPrograms()
                mapper.lockAllExecuted()
                mapper.lockAllSplits()
            }
        }

        expect("lockProgramGraph stops after the program lookup when the program is gone") {
            every { mapper.lockProgram(2) } returns null
            locks.lockProgramGraph(2) shouldBe null
            every { programs.find(3) } returns null
            locks.lockProgramGraph(3) shouldBe null
            verify(exactly = 0) {
                mapper.lockExecuted(any())
                mapper.lockSplits(any())
                mapper.lockReferences(any())
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
