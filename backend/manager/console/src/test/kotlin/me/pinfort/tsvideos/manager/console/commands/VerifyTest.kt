package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.command.LibraryVerificationCommand
import me.pinfort.tsvideos.core.domain.FileFinding
import me.pinfort.tsvideos.core.domain.VerificationReport
import me.pinfort.tsvideos.core.domain.VerificationStatus
import me.pinfort.tsvideos.manager.console.parseManager
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant

class VerifyTest :
    ExpectSpec({
        lateinit var command: LibraryVerificationCommand
        beforeTest { command = mockk() }
        expect("empty library succeeds and emits exactly one JSON object") {
            every { command.verify(null, any()) } returns VerificationReport(emptyList())
            val output = captureVerifyOutput { Verify(command).parseManager(listOf("--json")) }
            val tree = JsonMapper.builder().build().readTree(output)
            tree["schemaVersion"].intValue() shouldBe 1
            tree["findings"].size() shouldBe 0
            tree["summary"]["VERIFIED"].intValue() shouldBe 0
        }
        expect("issues retain machine-readable output and exit four") {
            every { command.verify(7, any()) } returns
                VerificationReport(
                    listOf(
                        FileFinding(
                            programId = 7,
                            fileId = 8,
                            file = "番組/\"test\".mp4",
                            status = VerificationStatus.MISSING,
                            checkedAt = Instant.parse("2026-01-01T00:00:00Z"),
                            expectedBytes = 12,
                            actualBytes = null,
                            reason = "Absent",
                        ),
                    ),
                )
            val output =
                captureVerifyOutput {
                    shouldThrow<ProgramResult> { Verify(command).parseManager(listOf("--program", "7", "--json")) }.statusCode shouldBe 4
                }
            val tree = JsonMapper.builder().build().readTree(output)
            tree["findings"][0]["file"].stringValue() shouldBe "番組/\"test\".mp4"
            tree["findings"][0]["checkedAt"].stringValue() shouldBe "2026-01-01T00:00:00Z"
            tree["findings"][0]["actualBytes"].isNull shouldBe true
        }
        expect("human output lists each finding and a summary") {
            every { command.verify(null, any()) } returns
                VerificationReport(
                    listOf(
                        FileFinding(
                            programId = 7,
                            fileId = 8,
                            file = "base/a.mp4",
                            status = VerificationStatus.VERIFIED,
                            checkedAt = Instant.parse("2026-01-01T00:00:00Z"),
                            expectedBytes = 12,
                            actualBytes = 12,
                            reason = null,
                        ),
                        FileFinding(
                            programId = 9,
                            fileId = null,
                            file = null,
                            status = VerificationStatus.UNCHECKED,
                            checkedAt = Instant.parse("2026-01-01T00:00:01Z"),
                            expectedBytes = null,
                            actualBytes = null,
                            reason = "No NAS artifacts registered",
                        ),
                    ),
                )
            val output = captureVerifyOutput { shouldThrow<ProgramResult> { Verify(command).parseManager(emptyList()) } }
            // Compare whitespace-separated fields, since the terminal renderer expands the tab separators.
            output.lines().filter { it.isNotBlank() }.map { it.trim().split(Regex("\\s+")) } shouldBe
                listOf(
                    listOf("VERIFIED", "program=7", "file=8", "base/a.mp4"),
                    listOf("expected=12", "actual=12", "checkedAt=2026-01-01T00:00:00Z"),
                    listOf("UNCHECKED", "program=9", "file=-", "-"),
                    listOf("expected=-", "actual=-", "checkedAt=2026-01-01T00:00:01Z", "No", "NAS", "artifacts", "registered"),
                    listOf("VERIFIED=1", "MISSING=0", "SIZE_MISMATCH=0", "INACCESSIBLE=0", "UNCHECKED=1"),
                )
        }
        expect("progress goes to stderr, leaving JSON stdout a single object") {
            every { command.verify(null, any()) } answers {
                val onProgress = secondArg<(Int, Int) -> Unit>()
                (1..250).forEach { onProgress(it, 250) }
                VerificationReport(emptyList())
            }
            val previousErr = System.err
            val err = ByteArrayOutputStream()
            val output =
                try {
                    System.setErr(PrintStream(err, true, Charsets.UTF_8))
                    captureVerifyOutput { Verify(command).parseManager(listOf("--json")) }
                } finally {
                    System.setErr(previousErr)
                }
            JsonMapper
                .builder()
                .build()
                .readTree(output)["schemaVersion"]
                .intValue() shouldBe 1
            output.trim().lines().size shouldBe 1
            err.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() } shouldBe
                listOf("Checked 100/250 programs", "Checked 200/250 programs", "Checked 250/250 programs")
        }
        expect("missing selected program exits three") {
            every { command.verify(9, any()) } returns null
            shouldThrow<CliktError> { Verify(command).parseManager(listOf("--program", "9")) }.statusCode shouldBe 3
        }
        expect("database errors exit one without exposing connection secrets") {
            every { command.verify(null, any()) } throws IllegalStateException("password=secret")
            val error = shouldThrow<CliktError> { Verify(command).parseManager(emptyList()) }
            error.statusCode shouldBe 1
            error.message shouldBe "Library verification failed"
        }
        expect("invalid IDs and undefined deep mode exit two") {
            shouldThrow<CliktError> { Verify(command).parseManager(listOf("--program", "0")) }.statusCode shouldBe 2
            shouldThrow<CliktError> { Verify(command).parseManager(listOf("--deep")) }.statusCode shouldBe 2
        }
    })

private fun captureVerifyOutput(block: () -> Unit): String {
    val previous = System.out
    val bytes = ByteArrayOutputStream()
    try {
        System.setOut(PrintStream(bytes, true, Charsets.UTF_8))
        block()
    } finally {
        System.setOut(previous)
    }
    return bytes.toString(Charsets.UTF_8)
}
