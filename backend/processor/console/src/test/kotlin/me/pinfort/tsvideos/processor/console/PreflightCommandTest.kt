package me.pinfort.tsvideos.processor.console

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.command.ProcessingPreflightCommand
import me.pinfort.tsvideos.core.domain.DiagnosticCheck
import me.pinfort.tsvideos.core.domain.DiagnosticReport
import me.pinfort.tsvideos.core.domain.DiagnosticStatus
import tools.jackson.databind.json.JsonMapper

class PreflightCommandTest :
    ExpectSpec({
        expect("JSON preserves all findings and dryrun never requests write") {
            val diagnostics = mockk<ProcessingPreflightCommand>()
            every { diagnostics.diagnose(any(), false) } returns
                DiagnosticReport(
                    listOf(
                        DiagnosticCheck("input.0", DiagnosticStatus.PASS, "録画"),
                        DiagnosticCheck("space.0", DiagnosticStatus.FAIL, "Insufficient"),
                    ),
                )
            val result = PreflightCommand(diagnostics).test(listOf("--json", "--dry-run", "/recording"))
            result.statusCode shouldBe 1
            val json = JsonMapper.builder().build().readTree(result.stdout)
            json["schemaVersion"].asInt() shouldBe 1
            json["checks"].size() shouldBe 2
            json["checks"][0]["reason"].asString() shouldBe "録画"
            verify(exactly = 0) { diagnostics.diagnose(any(), true) }
        }
        expect("process gate fails before processor startup and never writes probes") {
            val diagnostics = mockk<ProcessingPreflightCommand>()
            every { diagnostics.diagnose(any(), false) } returns
                DiagnosticReport(listOf(DiagnosticCheck("tool.ffprobe", DiagnosticStatus.FAIL, "Missing")))
            PreflightCommand(diagnostics, true).test(listOf("--dry-run", "/recording")).statusCode shouldBe 1
            verify(exactly = 1) { diagnostics.diagnose(any(), false) }
            verify(exactly = 0) { diagnostics.diagnose(any(), true) }
        }
        expect("explicit write is only available without dryrun") {
            val diagnostics = mockk<ProcessingPreflightCommand>()
            every { diagnostics.diagnose(any(), true) } returns DiagnosticReport(emptyList())
            PreflightCommand(diagnostics).test(listOf("--check-write", "/recording")).statusCode shouldBe 0
            val result = PreflightCommand(diagnostics).test(listOf("--dry-run", "--check-write", "/recording"))
            (result.statusCode != 0) shouldBe true
            verify(exactly = 1) { diagnostics.diagnose(any(), any()) }
        }
    })
