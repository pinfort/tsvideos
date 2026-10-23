package me.pinfort.tsvideos.diagnostics.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.diagnostics.domain.DiagnosticStatus
import me.pinfort.tsvideos.storage.api.DiagnosticNasClient
import org.springframework.mock.env.MockEnvironment
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility

/**
 * diagnostics' public contract: doctor/preflight assemble it by hand without a Spring context (DoctorBootstrap,
 * PreflightBootstrap), and NAS access goes only through storage's diagnostic connection.
 */
class DiagnosticsContractTest :
    ExpectSpec({
        val publicTypes: List<KClass<*>> =
            listOf(EnvironmentDiagnosticCommand::class, DatabaseDiagnosticProbe::class, NasDiagnosticProbe::class)

        fun signatures(type: KClass<*>): List<String> {
            val members = type.members.filter { it.visibility == KVisibility.PUBLIC }
            val constructors = type.constructors.filter { it.visibility == KVisibility.PUBLIC }
            val memberSignatures =
                members.flatMap { member ->
                    (member.parameters.map { it.type } + member.returnType).map { "${type.simpleName}.${member.name}: $it" }
                }
            val constructorSignatures =
                constructors.flatMap { constructor ->
                    constructor.parameters.map { "${type.simpleName}(): ${it.type}" }
                }
            return memberSignatures + constructorSignatures
        }

        expect("the command graph is built from public constructors without a Spring context") {
            publicTypes.forEach { type ->
                type.constructors.count { it.visibility == KVisibility.PUBLIC } shouldBe 1
            }
            val nas = NasDiagnosticProbe(DiagnosticNasClient.create())
            val command = EnvironmentDiagnosticCommand(MockEnvironment(), DatabaseDiagnosticProbe(), nas)
            // Invalid configuration never reaches a connection, so this stays offline.
            val report = command.diagnose()
            report.hasFailures shouldBe true
            val connectionStatuses =
                report.checks
                    .filterNot { it.id.startsWith("config.") }
                    .map { it.status }
                    .toSet()
            connectionStatuses shouldBe setOf(DiagnosticStatus.UNCHECKED)
        }

        expect("no public member or constructor mentions a jcifs type") {
            val leaks = publicTypes.flatMap { signatures(it) }.filter { "jcifs." in it }
            leaks.shouldBeEmpty()
        }

        expect("diagnostics sources do not import jcifs") {
            val root = File("src/main/kotlin")
            root.isDirectory shouldBe true
            val offenders =
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { file -> file.readLines().any { it.startsWith("import jcifs.") } }
                    .map { it.relativeTo(root).invariantSeparatorsPath }
                    .toList()
            offenders.shouldBeEmpty()
        }
    })
