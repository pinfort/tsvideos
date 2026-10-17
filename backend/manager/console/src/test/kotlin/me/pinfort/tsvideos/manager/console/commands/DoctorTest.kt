package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.command.EnvironmentDiagnosticCommand
import me.pinfort.tsvideos.core.domain.DiagnosticCheck
import me.pinfort.tsvideos.core.domain.DiagnosticReport
import me.pinfort.tsvideos.core.domain.DiagnosticStatus
import me.pinfort.tsvideos.manager.console.parseManager
import tools.jackson.databind.json.JsonMapper

class DoctorTest :
    ExpectSpec({
        expect("JSON includes all checks and failures produce exit one") {
            val diagnostics = mockk<EnvironmentDiagnosticCommand>()
            every { diagnostics.diagnose(false) } returns
                DiagnosticReport(
                    listOf(
                        DiagnosticCheck("database.connectivity", DiagnosticStatus.FAIL, "Check credentials"),
                        DiagnosticCheck("nas.read", DiagnosticStatus.PASS, "Accessible"),
                        DiagnosticCheck("nas.write", DiagnosticStatus.UNCHECKED, "Use --check-write"),
                    ),
                )
            val result = Doctor(diagnostics).testDoctor(listOf("--json"))
            result.statusCode shouldBe 1
            result.stderr shouldBe ""
            val json = JsonMapper.builder().build().readTree(result.stdout)
            json["schemaVersion"].asInt() shouldBe 1
            json["checks"].size() shouldBe 3
            json["checks"][0]["status"].asString() shouldBe "FAIL"
        }
        expect("human output is actionable and unchecked optional writes do not fail") {
            val diagnostics = mockk<EnvironmentDiagnosticCommand>()
            every { diagnostics.diagnose(false) } returns
                DiagnosticReport(
                    listOf(DiagnosticCheck("nas.write", DiagnosticStatus.UNCHECKED, "Use --check-write")),
                )
            val result = Doctor(diagnostics).testDoctor(emptyList())
            result.statusCode shouldBe 0
            result.stdout shouldContain "UNCHECKED"
            result.stdout shouldContain "nas.write"
            result.stdout shouldContain "Use --check-write"
        }
        expect("write probes are explicitly opted in") {
            val diagnostics = mockk<EnvironmentDiagnosticCommand>()
            every { diagnostics.diagnose(true) } returns DiagnosticReport(emptyList())
            Doctor(diagnostics).testDoctor(listOf("--check-write")).statusCode shouldBe 0
        }
        expect("help does not invoke diagnostics and unknown options exit two") {
            val diagnostics = mockk<EnvironmentDiagnosticCommand>()
            Doctor(diagnostics).testDoctor(listOf("--help")).statusCode shouldBe 0
            Doctor(diagnostics).testDoctor(listOf("--unknown")).statusCode shouldBe 2
        }
    })

private fun Doctor.testDoctor(arguments: List<String>) = test(arguments) { parseManager(it) }
