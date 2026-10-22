package me.pinfort.tsvideos.core.command

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.api.ProgramInventory
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.core.domain.VerificationStatus
import me.pinfort.tsvideos.storage.api.NasFileVerifier
import java.time.LocalDateTime

class LibraryVerificationCommandTest :
    ExpectSpec({
        lateinit var programs: ProgramCommand
        lateinit var inventory: ProgramInventory
        lateinit var files: CreatedFileCatalog
        lateinit var nas: NasFileVerifier
        lateinit var session: NasFileVerifier.Session
        lateinit var command: LibraryVerificationCommand
        // A program whose recording row is gone, as catalog returns it (recording fields are defaults).
        val program =
            Program(
                id = 1,
                name = "program",
                executedFileId = 10,
                status = Program.Status.COMPLETED,
                drops = -1,
                size = 0,
                recordedAt = LocalDateTime.MIN,
                channel = "",
                title = "",
                channelName = "",
                duration = -1.0,
            )
        val file = CreatedFile(2, 3, "base/file.mp4", 12, "video/mp4", null, CreatedFile.Status.FILE_MOVED)

        beforeTest {
            programs = mockk()
            inventory = mockk()
            files = mockk()
            nas = mockk()
            session = mockk()
            command = LibraryVerificationCommand(programs, inventory, files, nas)
            every { nas.session<Any?>(any()) } answers { firstArg<(NasFileVerifier.Session) -> Any?>()(session) }
            every { programs.find(1) } returns program
            every { files.filesOfRecording(10) } returns listOf(file)
        }
        expect("selected program includes file identity and per-status summary") {
            every { session.verify(file.file, 12) } returns NasFileVerifier.Result(NasFileVerifier.Status.SIZE_MISMATCH, 11)
            val report = command.verify(1).shouldNotBeNull()
            report.findings.single().programId shouldBe 1
            report.findings.single().fileId shouldBe 2
            report.findings.single().expectedBytes shouldBe 12
            report.findings.single().actualBytes shouldBe 11
            report.summary[VerificationStatus.SIZE_MISMATCH] shouldBe 1
            report.summary[VerificationStatus.MISSING] shouldBe 0
            report.hasIssues shouldBe true
            verify(exactly = 0) { inventory.allPrograms() }
        }
        expect("library reads programs and files in batch, in one NAS session, and reports progress") {
            every { inventory.allPrograms() } returns listOf(program, program.copy(id = 2, executedFileId = 20))
            every { files.filesByRecording() } returns mapOf(10L to listOf(file))
            every { session.verify(any(), any()) } returns NasFileVerifier.Result(NasFileVerifier.Status.VERIFIED, 12)
            val progress = mutableListOf<Pair<Int, Int>>()
            val report = command.verify { checked, total -> progress += checked to total }.shouldNotBeNull()
            report.findings.map { it.status } shouldBe listOf(VerificationStatus.VERIFIED, VerificationStatus.UNCHECKED)
            report.findings.last().fileId shouldBe null
            progress shouldBe listOf(1 to 2, 2 to 2)
            verify(exactly = 1) { nas.session<Any?>(any()) }
            verify(exactly = 0) {
                programs.find(any())
                files.filesOfRecording(any())
            }
        }
        expect("incomplete transfers remain unchecked without accessing NAS") {
            every { files.filesOfRecording(10) } returns listOf(file.copy(status = CreatedFile.Status.REGISTERED))
            command
                .verify(1)
                .shouldNotBeNull()
                .findings
                .single()
                .status shouldBe VerificationStatus.UNCHECKED
            verify(exactly = 0) { session.verify(any(), any()) }
        }
        // catalog returns a recording's files sorted by ID (CreatedFileCatalogTest); findings keep that order.
        expect("an inaccessible file does not prevent checking subsequent files, in catalog order") {
            every { files.filesOfRecording(10) } returns listOf(file, file.copy(id = 4, file = "base/second.mp4"))
            every { session.verify(file.file, 12) } returns NasFileVerifier.Result(NasFileVerifier.Status.INACCESSIBLE)
            every { session.verify("base/second.mp4", 12) } returns NasFileVerifier.Result(NasFileVerifier.Status.VERIFIED, 12)
            command
                .verify(1)
                .shouldNotBeNull()
                .findings
                .map { it.status } shouldBe
                listOf(VerificationStatus.INACCESSIBLE, VerificationStatus.VERIFIED)
        }
        // Persisted health findings, API/CLI output and `tvmcli verify`'s exit code read VerificationStatus names.
        expect("every low-level storage status becomes the same-named verification status, with size and reason") {
            NasFileVerifier.Status.entries.forEach { status ->
                every { session.verify(file.file, 12) } returns NasFileVerifier.Result(status, 13, "reason ${status.name}")
                val finding =
                    command
                        .verify(1)
                        .shouldNotBeNull()
                        .findings
                        .single()
                finding.status shouldBe VerificationStatus.valueOf(status.name)
                finding.actualBytes shouldBe 13
                finding.reason shouldBe "reason ${status.name}"
            }
            VerificationStatus.entries.map { it.name } shouldBe NasFileVerifier.Status.entries.map { it.name }
        }
        expect("missing requested program differs from an empty library") {
            every { programs.find(99) } returns null
            command.verify(99) shouldBe null
            every { inventory.allPrograms() } returns emptyList()
            every { files.filesByRecording() } returns emptyMap()
            command.verify().shouldNotBeNull().hasIssues shouldBe false
        }
    })
