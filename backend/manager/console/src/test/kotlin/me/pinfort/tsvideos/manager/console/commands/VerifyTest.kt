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
            // Mordant expands the tab separators to spaces, so compare with whitespace runs collapsed.
            output.replace("\r\n", "\n").replace(Regex("[ \t]+"), " ") shouldBe
                """
                VERIFIED program=7 file=8 base/a.mp4
                 expected=12 actual=12 checkedAt=2026-01-01T00:00:00Z${' '}
                UNCHECKED program=9 file=- -
                 expected=- actual=- checkedAt=2026-01-01T00:00:01Z No NAS artifacts registered
                VERIFIED=1 MISSING=0 SIZE_MISMATCH=0 INACCESSIBLE=0 UNCHECKED=1

                """.trimIndent()
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
