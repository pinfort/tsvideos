package me.pinfort.tsvideos.catalog.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.catalog.persistence.BulkLibraryMapper
import me.pinfort.tsvideos.catalog.persistence.CreatedFileDto
import me.pinfort.tsvideos.catalog.persistence.CreatedFileMapper
import me.pinfort.tsvideos.catalog.persistence.ExecutedFileDto
import me.pinfort.tsvideos.catalog.persistence.ExecutedFileMapper
import me.pinfort.tsvideos.catalog.persistence.ProgramDto
import me.pinfort.tsvideos.catalog.persistence.ProgramMapper
import me.pinfort.tsvideos.catalog.persistence.SplittedFileDto
import me.pinfort.tsvideos.catalog.persistence.SplittedFileMapper
import java.time.LocalDateTime
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * How a program snapshot is assembled from the ledger rows. The bulk preview publishes these snapshots as JSON and
 * fingerprints every property (library's BulkLibraryCommandTest), so they must carry exactly the stored columns.
 */
class LedgerSnapshotsTest :
    ExpectSpec({
        val programs = mockk<ProgramMapper>()
        val executed = mockk<ExecutedFileMapper>()
        val splits = mockk<SplittedFileMapper>()
        val created = mockk<CreatedFileMapper>()
        val bulk = mockk<BulkLibraryMapper>()
        val snapshots = LedgerSnapshots(programs, executed, splits, created, bulk)
        val at = LocalDateTime.of(2026, 1, 1, 0, 0)
        val program = ProgramDto(1, "first", 10, ProgramDto.Status.COMPLETED, 0, 12, at, "1", "t", "c", 12.0)
        val source = ExecutedFileDto(10, "C:\\original.m2ts", 0, 12, at, "1", "t", "c", 12.0, ExecutedFileDto.Status.SPLITTED)
        val split = SplittedFileDto(20, 10, "C:\\split.m2ts", 12, 12.0, SplittedFileDto.Status.ENCODE_TASK_ADDED)
        val file = CreatedFileDto(30, 20, "base/a/alpha/video.mp4", 12, "video/mp4", null, CreatedFileDto.Status.FILE_MOVED, 1920, 1080)

        beforeTest { clearMocks(programs, executed, splits, created, bulk) }

        expect("a program snapshot holds the program and its recording, splits and created files, each by id") {
            every { programs.find(1) } returns program
            every { executed.find(10) } returns source
            every { splits.selectByExecutedFileId(10) } returns listOf(split.copy(id = 21), split)
            every { created.selectByExecutedFileId(10) } returns listOf(file.copy(id = 31), file)
            val snapshot = snapshots.programSnapshot(1).shouldNotBeNull()
            snapshot.program shouldBe program.toDomain()
            snapshot.executed shouldBe source.toDomain()
            snapshot.splitFiles.map { it.id } shouldBe listOf(20L, 21L)
            snapshot.createdFiles.map { it.id } shouldBe listOf(30L, 31L)
            snapshot.createdFiles.first() shouldBe
                CreatedFileSnapshot(30, 20, file.file, 12, "video/mp4", null, file.toDomain().status, 1920, 1080)
        }

        expect("a missing program has no snapshot and reads nothing else") {
            every { programs.find(2) } returns null
            snapshots.programSnapshot(2) shouldBe null
            verify(exactly = 0) { executed.find(any()) }
        }

        expect("snapshot types publish the stored row properties only") {
            fun names(type: KClass<*>) = type.memberProperties.map { it.name }.toSet()
            names(ExecutedFile::class) shouldBe names(ExecutedFileDto::class)
            names(SplittedFile::class) shouldBe names(SplittedFileDto::class)
            names(CreatedFileSnapshot::class) shouldBe names(CreatedFileDto::class)
        }

        expect("bulk reads convert every stored row") {
            every { bulk.allFiles() } returns listOf(file)
            every { bulk.createdFilesOfPrograms(listOf(1, 1)) } returns listOf(file, file)
            every { bulk.references(10) } returns listOf(1, 2)
            snapshots.allCreatedFiles().map { it.id } shouldBe listOf(30L)
            snapshots.createdFilesOfPrograms(listOf(1, 1)).size shouldBe 2
            snapshots.referencingPrograms(10) shouldBe listOf(1L, 2L)
        }
    })
