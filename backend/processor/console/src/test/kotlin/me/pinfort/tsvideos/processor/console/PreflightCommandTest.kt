package me.pinfort.tsvideos.processor.console

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.diagnostics.api.DiagnosticEnvironment
import me.pinfort.tsvideos.diagnostics.domain.DiagnosticCheck
import me.pinfort.tsvideos.diagnostics.domain.DiagnosticReport
import me.pinfort.tsvideos.diagnostics.domain.DiagnosticStatus
import me.pinfort.tsvideos.processing.api.ProcessingPreflightCommand
import me.pinfort.tsvideos.storage.api.NasComponent
import me.pinfort.tsvideos.storage.api.VerifiedUploadNas
import org.springframework.context.annotation.AnnotationConfigApplicationContext
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
        // Standalone preflight reads the DB settings from this console's own application-datasource.yaml (core's until
        // #139), the NAS settings from the storage module's application-storage.yaml and the tool settings from the
        // processing module's application-processing.yaml.
        expect("the standalone environment loads the datasource, storage and processing profile defaults") {
            val environment = DiagnosticEnvironment.load(emptyArray())
            environment.activeProfiles.toList().contains("datasource") shouldBe true
            environment.activeProfiles.toList().contains("storage") shouldBe true
            environment.activeProfiles.toList().contains("processing") shouldBe true
            environment.getProperty("spring.datasource.url")!!.startsWith("jdbc:") shouldBe true
            environment.getProperty("spring.datasource.hikari.auto-commit") shouldBe "false"
            environment.getProperty("spring.datasource.hikari.maximum-pool-size") shouldBe "10"
            environment.getProperty("samba.video-store-nas.url")!!.startsWith("smb:") shouldBe true
            environment.getProperty("samba.original-store-nas.url")!!.startsWith("smb:") shouldBe true
            environment.getProperty("processor-tool.ffprobe-path") shouldBe (System.getenv("FFPROBE_PATH") ?: "ffprobe")
            environment.getProperty("processor-tool.amatsukaze.port") shouldBe (System.getenv("AMATSUKAZE_PORT") ?: "32768")
            environment.getProperty("processor-preflight.space-multiplier") shouldBe
                (System.getenv("PROCESSOR_PREFLIGHT_SPACE_MULTIPLIER") ?: "3")
        }
        // ConsoleApplication scans storage; its beans must assemble from this console's own configuration alone.
        expect("the storage beans assemble from the console's loaded configuration") {
            AnnotationConfigApplicationContext()
                .apply {
                    environment = DiagnosticEnvironment.load(emptyArray())
                    scan("me.pinfort.tsvideos.storage")
                    refresh()
                }.use { context ->
                    context.getBean(NasComponent::class.java)
                    context.getBean(VerifiedUploadNas::class.java)
                }
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
        expect("authored option-conflict messages are shown verbatim with exit 2") {
            val out = ByteArrayOutputStream()
            val originalOut = System.out
            val originalErr = System.err
            val status =
                try {
                    PrintStream(out, true, Charsets.UTF_8).let {
                        System.setOut(it)
                        System.setErr(it)
                    }
                    PreflightBootstrap.run(arrayOf("preflight", "--dry-run", "--check-write", "/recording"))
                } finally {
                    System.setOut(originalOut)
                    System.setErr(originalErr)
                }
            status shouldBe 2
            out.toString(Charsets.UTF_8).contains("--check-write is only available for explicit non-dry-run preflight") shouldBe true
        }
        expect("help skips configuration loading and exits 0") {
            PreflightBootstrap.run(arrayOf("preflight", "--help", "--spring.config.location=file:/nonexistent/x.yaml")) shouldBe 0
        }
    })
