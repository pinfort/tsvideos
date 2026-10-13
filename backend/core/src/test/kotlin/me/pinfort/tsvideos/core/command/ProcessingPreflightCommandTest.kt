package me.pinfort.tsvideos.core.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.component.EncoderConnectivityProbe
import me.pinfort.tsvideos.core.component.LocalPreflightProbe
import me.pinfort.tsvideos.core.domain.DiagnosticReport
import me.pinfort.tsvideos.core.domain.DiagnosticStatus
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Path

class ProcessingPreflightCommandTest :
    ExpectSpec({
        lateinit var env: MockEnvironment
        lateinit var shared: EnvironmentDiagnosticCommand
        lateinit var local: LocalPreflightProbe
        lateinit var tcp: EncoderConnectivityProbe
        lateinit var command: ProcessingPreflightCommand
        val one = Path.of("one").toAbsolutePath()
        val two = Path.of("two").toAbsolutePath()
        beforeTest {
            env =
                MockEnvironment()
                    .withProperty(
                        "processor-tool.amatsukaze.host",
                        "localhost",
                    ).withProperty("processor-tool.amatsukaze.port", "1234")
            listOf(
                "ts-splitter-path",
                "amatsukaze-add-task-path",
                "ffprobe-path",
            ).forEach { env.withProperty("processor-tool.$it", "tool") }
            listOf("default-profile", "atx-div-profile").forEach { env.withProperty("processor-tool.amatsukaze.$it", "profile") }
            shared = mockk { every { diagnose(any()) } returns DiagnosticReport(emptyList()) }
            local =
                mockk {
                    every { expand(any()) } answers { listOf(firstArg()) }
                    every { inspect(any()) } answers
                        { LocalPreflightProbe.Input(firstArg(), 10, "same-store", 50) }
                    every { tool(any(), any()) } returns true
                    every {
                        windows()
                    } returns false
                }
            tcp = mockk { every { check(any(), any()) } returns true }
            command = ProcessingPreflightCommand(env, shared, local, tcp)
        }
        expect("same filesystem sums inputs from different directories and does not double count aliases") {
            val report = command.diagnose(listOf(one, two, one))
            report.checks.single { it.id == "space.0" }.status shouldBe DiagnosticStatus.FAIL
            report.checks
                .single { it.id == "space.0" }
                .reason
                .contains("60 bytes") shouldBe true
            report.checks.count { it.id.endsWith(".execution") && it.status == DiagnosticStatus.UNCHECKED } shouldBe 2
            verify(exactly = 1) { shared.diagnose(false) }
        }
        expect("zero usable space fails while unavailable space stays unchecked and overflow is explicit") {
            every { local.inspect(any()) } returns LocalPreflightProbe.Input(one, 10, "store", 0)
            command
                .diagnose(listOf(one))
                .checks
                .single { it.id == "space.0" }
                .status shouldBe DiagnosticStatus.FAIL
            every { local.inspect(any()) } returns LocalPreflightProbe.Input(one, 10, null, null)
            command
                .diagnose(listOf(one))
                .checks
                .single { it.id == "space.0" }
                .status shouldBe DiagnosticStatus.UNCHECKED
            every { local.inspect(any()) } returns LocalPreflightProbe.Input(one, Long.MAX_VALUE, "store", Long.MAX_VALUE)
            command
                .diagnose(listOf(one))
                .checks
                .single { it.id == "space.0" }
                .reason
                .contains("supported byte range") shouldBe true
        }
        expect("all local and config problems are reported without secrets") {
            every { local.inspect(any()) } throws IllegalStateException("credential-secret")
            every { local.tool(any(), any()) } throws IllegalStateException("credential-secret")
            env.withProperty("processor-tool.amatsukaze.host", "user:credential-secret@host")
            env.withProperty("processor-tool.amatsukaze.port", "bad-secret")
            env.withProperty("processor-preflight.space-multiplier", "NaN")
            val report = command.diagnose(listOf(one))
            report.checks.count { it.status == DiagnosticStatus.FAIL } shouldBe 7
            report.toString().contains("secret") shouldBe false
            verify(exactly = 0) { tcp.check(any(), any()) }
        }
        expect("failed encoder does not suppress disk checks and explicit write request is forwarded") {
            every { tcp.check(any(), any()) } returns false
            val report = command.diagnose(listOf(one), true)
            report.checks.single { it.id == "encoder.connectivity" }.status shouldBe DiagnosticStatus.FAIL
            report.checks.single { it.id == "space.0" }.status shouldBe DiagnosticStatus.PASS
            verify { shared.diagnose(true) }
        }
        expect("interruption is never converted into an unavailable finding") {
            every { tcp.check(any(), any()) } throws InterruptedException()
            try {
                shouldThrow<InterruptedException> { command.diagnose(listOf(one)) }
                Thread.currentThread().isInterrupted shouldBe true
            } finally {
                Thread.interrupted()
            }
        }
    })
