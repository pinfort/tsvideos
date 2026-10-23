package me.pinfort.tsvideos.diagnostics.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.pinfort.tsvideos.diagnostics.domain.DiagnosticStatus
import me.pinfort.tsvideos.storage.api.DiagnosticNasClient
import me.pinfort.tsvideos.storage.api.NasAccessException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The probe over the storage diagnostic contract. The Samba adapter itself (O_EXCL flags, close ownership and
 * failure translation) is covered by storage's SambaDiagnosticNasClientTest.
 */
class NasDiagnosticProbeTest :
    ExpectSpec({
        lateinit var client: DiagnosticNasClient
        lateinit var connection: DiagnosticNasClient.Connection
        lateinit var directory: DiagnosticNasClient.Directory
        lateinit var file: DiagnosticNasClient.FileHandle
        lateinit var output: ByteArrayOutputStream
        lateinit var probe: NasDiagnosticProbe
        beforeTest {
            client = mockk()
            connection = mockk(relaxed = true)
            directory = mockk(relaxed = true)
            file = mockk(relaxed = true)
            output = ByteArrayOutputStream()
            every { client.open(any(), any(), any()) } returns connection
            every { connection.directory("base") } returns directory
            every { directory.isDirectory() } returns true
            every { directory.listFirstEntry() } just Runs
            every { directory.file(any()) } returns file
            every { file.createExclusive() } returns output
            every { file.openInputStream() } answers { ByteArrayInputStream(output.toByteArray()) }
            probe = NasDiagnosticProbe(client)
        }

        fun run(write: Boolean = false) = probe.check("nas.test", "smb://host/share", "user", "secret", "base", write)

        expect("interrupted owned write still cleans up and propagates cancellation") {
            every { file.openInputStream() } throws IOException("wrapped", InterruptedException())
            try {
                shouldThrow<InterruptedException> { run(true) }
                verify(exactly = 1) { file.delete() }
            } finally {
                Thread.interrupted()
            }
        }
        expect("default checks directory and listing but never writes, closing what it opened") {
            val checks = run()
            checks.single { it.id.endsWith(".read") }.status shouldBe DiagnosticStatus.PASS
            checks.single { it.id.endsWith(".write") }.status shouldBe DiagnosticStatus.UNCHECKED
            verify(exactly = 1) { client.open("smb://host/share", "user", "secret") }
            verify(exactly = 1) { directory.listFirstEntry() }
            verify(exactly = 0) { directory.file(any()) }
            verify {
                directory.close()
                connection.close()
            }
        }
        expect("blank base directory is passed to the connection as is") {
            every { connection.directory("") } returns directory
            val checks = probe.check("nas.test", "smb://host/share", "user", "secret", "", false)
            checks.single { it.id.endsWith(".read") }.status shouldBe DiagnosticStatus.PASS
            verify(exactly = 1) { connection.directory("") }
        }
        expect("missing directory reports actionable failure without listing or writing") {
            every { directory.isDirectory() } returns false
            val checks = run(true)
            checks.first().status shouldBe DiagnosticStatus.FAIL
            checks.last().status shouldBe DiagnosticStatus.UNCHECKED
            verify(exactly = 0) { directory.listFirstEntry() }
            verify(exactly = 0) { directory.file(any()) }
        }
        expect("denied listing is a safe failure and no write is attempted") {
            every { directory.listFirstEntry() } throws IOException("password=secret")
            val checks = run(true)
            checks.first().status shouldBe DiagnosticStatus.FAIL
            checks.toString() shouldNotContain "secret"
            verify(exactly = 0) { file.createExclusive() }
        }
        expect("write probe uses an isolated name, verifies bytes and deletes its own file") {
            val name = slot<String>()
            every { directory.file(capture(name)) } returns file
            val checks = run(true)
            checks.all { it.status == DiagnosticStatus.PASS } shouldBe true
            name.captured shouldContain ".tsvideos-doctor-"
            name.captured.endsWith(".tmp") shouldBe true
            output.toString(Charsets.UTF_8) shouldBe "tsvideos-doctor\n"
            verify(exactly = 1) {
                file.delete()
            }
            verify { file.close() }
        }
        expect("write failure still removes the probe") {
            every { file.createExclusive() } returns
                object : OutputStream() {
                    override fun write(value: Int) = throw IOException("secret")
                }
            val checks = run(true)
            checks.single { it.id.endsWith(".write") }.status shouldBe DiagnosticStatus.FAIL
            checks.single { it.id.endsWith(".cleanup") }.status shouldBe DiagnosticStatus.PASS
            verify { file.delete() }
        }
        expect("readback mismatch still removes the probe") {
            every { file.openInputStream() } returns ByteArrayInputStream(byteArrayOf(1))
            run(true).single { it.id.endsWith(".write") }.status shouldBe DiagnosticStatus.FAIL
            verify { file.delete() }
        }
        expect("cleanup failure names the isolated probe without exposing storage credentials") {
            every { file.delete() } throws IOException("secret")
            val result = run(true).single { it.id.endsWith(".cleanup") }
            result.status shouldBe DiagnosticStatus.FAIL
            result.reason shouldContain ".tsvideos-doctor-"
            result.reason shouldNotContain "secret"
        }
        expect("failed exclusive creation does not delete a file it does not own") {
            every { file.createExclusive() } throws NasAccessException("NAS exclusive create failed")
            run(true).single { it.id.endsWith(".write") }.status shouldBe DiagnosticStatus.FAIL
            verify(exactly = 0) { file.delete() }
        }
        expect("write and cleanup failures are both reported") {
            every { file.createExclusive() } returns
                object : OutputStream() {
                    override fun write(value: Int) = throw IOException("secret")
                }
            every { file.delete() } throws IOException("secret")
            val checks = run(true)
            checks.single { it.id.endsWith(".write") }.status shouldBe DiagnosticStatus.FAIL
            checks.single { it.id.endsWith(".cleanup") }.status shouldBe DiagnosticStatus.FAIL
        }
        expect("interruption is propagated with the interrupt flag restored, after cleanup") {
            every { file.openInputStream() } throws InterruptedException()
            shouldThrow<InterruptedException> { run(true) }
            Thread.interrupted() shouldBe true
            verify { file.delete() }
        }
        expect("interruption during cleanup is propagated, not reported as a cleanup failure") {
            every { file.delete() } throws InterruptedException()
            shouldThrow<InterruptedException> { run(true) }
            Thread.interrupted() shouldBe true
        }
        expect("a storage listing failure is a safe read failure") {
            every { directory.listFirstEntry() } throws NasAccessException("NAS directory listing failed", IOException("password=secret"))
            val checks = run(true)
            checks.first().status shouldBe DiagnosticStatus.FAIL
            checks.toString() shouldNotContain "secret"
        }
        expect("a connection failure is a safe read failure") {
            every { client.open(any(), any(), any()) } throws
                NasAccessException("NAS connect failed", IOException("smb://user:secret@host"))
            val checks = run(true)
            checks.single { it.id.endsWith(".read") }.status shouldBe DiagnosticStatus.FAIL
            checks.single { it.id.endsWith(".write") }.status shouldBe DiagnosticStatus.UNCHECKED
            checks.toString() shouldNotContain "secret"
        }
        expect("a failure after a successful read is a separate operation failure") {
            every { directory.close() } throws IOException("secret")
            val checks = run()
            checks.single { it.id.endsWith(".read") }.status shouldBe DiagnosticStatus.PASS
            checks.single { it.id.endsWith(".operation") }.status shouldBe DiagnosticStatus.FAIL
            checks.single { it.id.endsWith(".write") }.status shouldBe DiagnosticStatus.UNCHECKED
            checks.toString() shouldNotContain "secret"
        }
    })
