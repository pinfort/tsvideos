package me.pinfort.tsvideos.core.command

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.domain.VerificationStatus
import me.pinfort.tsvideos.core.external.database.dto.CreatedFileDto
import me.pinfort.tsvideos.core.external.database.dto.ProgramDto
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import me.pinfort.tsvideos.core.external.samba.NasFileVerifier

class LibraryVerificationCommandTest :
    ExpectSpec({
        lateinit var programs: ProgramMapper
        lateinit var files: CreatedFileMapper
        lateinit var nas: NasFileVerifier
        lateinit var command: LibraryVerificationCommand
        val program = ProgramDto(1, "program", 10, ProgramDto.Status.COMPLETED, null, null, null, null, null, null, null)
        val file = CreatedFileDto(2, 3, "base/file.mp4", 12, "video/mp4", null, CreatedFileDto.Status.FILE_MOVED)
        beforeTest {
            programs = mockk()
            files = mockk()
            nas = mockk()
            command = LibraryVerificationCommand(programs, files, nas)
            every { programs.find(1) } returns program
            every { files.selectByExecutedFileId(10) } returns listOf(file)
        }
        expect("selected program includes file identity and per-status summary") {
            every { nas.verify(file.file, 12) } returns NasFileVerifier.Result(VerificationStatus.SIZE_MISMATCH, 11)
            val report = command.verify(1)!!
            report.findings.single().programId shouldBe 1
            report.findings.single().fileId shouldBe 2
            report.findings.single().expectedBytes shouldBe 12
            report.findings.single().actualBytes shouldBe 11
            report.summary[VerificationStatus.SIZE_MISMATCH] shouldBe 1
            report.summary[VerificationStatus.MISSING] shouldBe 0
            report.hasIssues shouldBe true
            verify(exactly = 0) { programs.selectAllIds() }
        }
        expect("library checks every ID including programs without source records and sorts files") {
            every { programs.selectAllIds() } returns listOf(1, 2)
            every { programs.find(2) } returns program.copy(id = 2, executedFileId = 20)
            every { files.selectByExecutedFileId(20) } returns emptyList()
            every { nas.verify(any(), any()) } returns NasFileVerifier.Result(VerificationStatus.VERIFIED, 12)
            val report = command.verify()!!
            report.findings.map { it.status } shouldBe listOf(VerificationStatus.VERIFIED, VerificationStatus.UNCHECKED)
            report.findings.last().fileId shouldBe null
        }
        expect("incomplete transfers remain unchecked without accessing NAS") {
            every { files.selectByExecutedFileId(10) } returns listOf(file.copy(status = CreatedFileDto.Status.REGISTERED))
            command
                .verify(1)!!
                .findings
                .single()
                .status shouldBe VerificationStatus.UNCHECKED
            verify(exactly = 0) { nas.verify(any(), any()) }
        }
        expect("an inaccessible file does not prevent checking subsequent files") {
            every { files.selectByExecutedFileId(10) } returns listOf(file.copy(id = 4, file = "base/second.mp4"), file)
            every { nas.verify(file.file, 12) } returns NasFileVerifier.Result(VerificationStatus.INACCESSIBLE)
            every { nas.verify("base/second.mp4", 12) } returns NasFileVerifier.Result(VerificationStatus.VERIFIED, 12)
            command.verify(1)!!.findings.map { it.status } shouldBe
                listOf(VerificationStatus.INACCESSIBLE, VerificationStatus.VERIFIED)
        }
        expect("missing requested program differs from an empty library") {
            every { programs.find(99) } returns null
            command.verify(99) shouldBe null
            every { programs.selectAllIds() } returns emptyList()
            command.verify()!!.hasIssues shouldBe false
        }
    })
