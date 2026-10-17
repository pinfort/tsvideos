package me.pinfort.tsvideos.core.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.component.DatabaseDiagnosticProbe
import me.pinfort.tsvideos.core.component.NasDiagnosticProbe
import me.pinfort.tsvideos.core.domain.DiagnosticCheck
import me.pinfort.tsvideos.core.domain.DiagnosticStatus
import org.springframework.mock.env.MockEnvironment

class EnvironmentDiagnosticCommandTest :
    ExpectSpec({
        lateinit var environment: MockEnvironment
        lateinit var database: DatabaseDiagnosticProbe
        lateinit var nas: NasDiagnosticProbe
        lateinit var command: EnvironmentDiagnosticCommand
        beforeTest {
            environment =
                MockEnvironment()
                    .withProperty("spring.datasource.url", "jdbc:mariadb://localhost:3306/test")
                    .withProperty("spring.datasource.username", "db-user")
                    .withProperty("spring.datasource.password", "secret-db")
            listOf("video-store-nas", "original-store-nas").forEach {
                environment.withProperty("samba.$it.url", "smb://localhost/share")
                environment.withProperty("samba.$it.username", "nas-user")
                environment.withProperty("samba.$it.password", "secret-nas")
                environment.withProperty("samba.$it.base-dir", "base")
            }
            database = mockk()
            nas = mockk()
            every { database.check(any(), any(), any()) } returns true
            every { nas.check(any(), any(), any(), any(), any(), any()) } answers {
                listOf(DiagnosticCheck("${firstArg<String>()}.read", DiagnosticStatus.PASS, "Accessible"))
            }
            command = EnvironmentDiagnosticCommand(environment, database, nas)
        }
        expect("database interruption and interrupt flag propagate without NAS checks") {
            every { database.check(any(), any(), any()) } throws java.sql.SQLException("wrapped", InterruptedException())
            try {
                shouldThrow<InterruptedException> { command.diagnose() }
                verify(exactly = 0) { nas.check(any(), any(), any(), any(), any(), any()) }
            } finally {
                Thread.interrupted()
            }
        }
        expect("valid configuration checks every endpoint without exposing credentials") {
            val report = command.diagnose()
            report.hasFailures shouldBe false
            report.checks.count { it.id.startsWith("config.") } shouldBe 11
            report.toString() shouldNotContain "secret"
            report.toString() shouldNotContain "db-user"
            verify(exactly = 2) { nas.check(any(), any(), any(), any(), "base", false) }
        }
        expect("database failure is isolated and NAS checks still run") {
            every { database.check(any(), any(), any()) } throws IllegalStateException("jdbc:secret-db")
            val report = command.diagnose(true)
            report.checks.single { it.id == "database.connectivity" }.status shouldBe DiagnosticStatus.FAIL
            report.checks.count { it.id.endsWith(".read") } shouldBe 2
            report.toString() shouldNotContain "secret-db"
            verify(exactly = 2) { nas.check(any(), any(), any(), any(), any(), true) }
        }
        expect("missing settings are identified individually with no connection attempts") {
            val report = EnvironmentDiagnosticCommand(MockEnvironment(), database, nas).diagnose()
            report.checks.count { it.status == DiagnosticStatus.FAIL } shouldBe 11
            report.checks.count { it.status == DiagnosticStatus.UNCHECKED } shouldBe 5
            verify(exactly = 0) { database.check(any(), any(), any()) }
            verify(exactly = 0) { nas.check(any(), any(), any(), any(), any(), any()) }
        }
        expect("embedded credentials and invalid base directories are not echoed or contacted") {
            environment.withProperty("spring.datasource.url", "jdbc:mariadb://secret-user:secret-pass@host/test")
            environment.withProperty("samba.video-store-nas.url", "smb://secret-user:secret-pass@host/share")
            environment.withProperty("samba.original-store-nas.base-dir", "../escape")
            val report = command.diagnose()
            report.checks.count { it.status == DiagnosticStatus.FAIL } shouldBe 3
            report.toString() shouldNotContain "secret"
            verify(exactly = 0) { database.check(any(), any(), any()) }
            verify(exactly = 0) { nas.check(any(), any(), any(), any(), any(), any()) }
        }
        expect("multiple endpoint failures all appear in the report") {
            every { database.check(any(), any(), any()) } returns false
            every { nas.check(any(), any(), any(), any(), any(), any()) } answers {
                listOf(DiagnosticCheck("${firstArg<String>()}.read", DiagnosticStatus.FAIL, "Check permissions"))
            }
            command.diagnose().checks.count { it.status == DiagnosticStatus.FAIL } shouldBe 3
        }
        expect("URL query credentials and encoded base-directory escapes fail validation") {
            environment.withProperty("spring.datasource.url", "jdbc:mariadb://host/test?password=secret")
            environment.withProperty("samba.video-store-nas.base-dir", "base/%2e%2e")
            environment.withProperty("samba.original-store-nas.base-dir", "base?redirect")
            command.diagnose().checks.count { it.status == DiagnosticStatus.FAIL } shouldBe 3
            verify(exactly = 0) { database.check(any(), any(), any()) }
            verify(exactly = 0) { nas.check(any(), any(), any(), any(), any(), any()) }
        }
        expect("a malformed single NAS does not suppress the healthy endpoint") {
            environment.withProperty("samba.video-store-nas.url", "not a URL")
            val report = command.diagnose()
            report.checks.single { it.id == "config.samba.video-store-nas.url" }.status shouldBe DiagnosticStatus.FAIL
            report.checks.single { it.id == "nas.original-store-nas.read" }.status shouldBe DiagnosticStatus.PASS
        }
        expect("a blank database password is accepted and left to the connectivity check") {
            environment.withProperty("spring.datasource.password", "")
            val report = command.diagnose()
            report.checks.single { it.id == "config.spring.datasource.password" }.status shouldBe DiagnosticStatus.PASS
            verify(exactly = 1) { database.check(any(), any(), "") }
        }
        expect("interruption during the database check is propagated, not reported as a failure") {
            every { database.check(any(), any(), any()) } throws InterruptedException()
            shouldThrow<InterruptedException> { command.diagnose() }
            Thread.interrupted() shouldBe true
        }
    })
