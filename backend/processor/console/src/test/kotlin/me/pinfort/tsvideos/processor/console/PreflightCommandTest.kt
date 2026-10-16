package me.pinfort.tsvideos.processor.console

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.command.ProcessingPreflightCommand
import me.pinfort.tsvideos.core.config.DiagnosticEnvironment
import me.pinfort.tsvideos.core.domain.DiagnosticCheck
import me.pinfort.tsvideos.core.domain.DiagnosticReport
import me.pinfort.tsvideos.core.domain.DiagnosticStatus
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream

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
        expect("option detection ignores operands after the -- separator") {
            hasOption(arrayOf("process", "--json", "/recording"), "--json") shouldBe true
            hasOption(arrayOf("process", "--", "--json"), "--json") shouldBe false
            hasOption(arrayOf("process", "--", "--help"), "--help") shouldBe false
        }
        expect("spring property arguments are split from command arguments") {
            val args = arrayOf("preflight", "--spring.config.additional-location=/x.yaml", "--spring.flag", "/recording")
            DiagnosticEnvironment.commandArguments(args) shouldBe listOf("preflight", "--spring.flag", "/recording")
            DiagnosticEnvironment.commandArguments(arrayOf("process", "--", "--spring.x=y")) shouldBe
                listOf("process", "--", "--spring.x=y")
        }
        expect("unloadable configuration reports config.load as JSON and exits 1") {
            val out = ByteArrayOutputStream()
            val original = System.out
            val status =
                try {
                    System.setOut(PrintStream(out, true, Charsets.UTF_8))
                    PreflightBootstrap.run(
                        arrayOf("preflight", "--json", "--spring.config.location=file:/nonexistent/tsvideos-preflight.yaml", "/recording"),
                    )
                } finally {
                    System.setOut(original)
                }
            status shouldBe 1
            val json = JsonMapper.builder().build().readTree(out.toString(Charsets.UTF_8))
            json["checks"][0]["id"].asString() shouldBe "config.load"
            json["checks"][0]["status"].asString() shouldBe "FAIL"
        }
        expect("help skips configuration loading and exits 0") {
            PreflightBootstrap.run(arrayOf("preflight", "--help", "--spring.config.location=file:/nonexistent/x.yaml")) shouldBe 0
        }
    })
