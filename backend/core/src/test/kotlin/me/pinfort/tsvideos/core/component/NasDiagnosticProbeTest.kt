package me.pinfort.tsvideos.core.component

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jcifs.CloseableIterator
import jcifs.SmbConstants
import jcifs.SmbResource
import me.pinfort.tsvideos.core.domain.DiagnosticStatus
import me.pinfort.tsvideos.core.external.samba.DiagnosticNasClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class NasDiagnosticProbeTest :
    ExpectSpec({
        lateinit var client: DiagnosticNasClient
        lateinit var root: SmbResource
        lateinit var directory: SmbResource
        lateinit var file: SmbResource
        lateinit var output: ByteArrayOutputStream
        lateinit var probe: NasDiagnosticProbe
        beforeTest {
            client = mockk()
            root = mockk(relaxed = true)
            directory = mockk(relaxed = true)
            file = mockk(relaxed = true)
            output = ByteArrayOutputStream()
            every { client.open(any(), any(), any()) } returns DiagnosticNasClient.Connection(root) {}
            every { root.resolve("base/") } returns directory
            every { directory.exists() } returns true
            every { directory.isDirectory } returns true
            val children = mockk<CloseableIterator<SmbResource>>(relaxed = true)
            every { children.hasNext() } returns false
            every { directory.children() } returns children
            every { directory.resolve(any<String>()) } returns file
            every {
                file.openOutputStream(
                    true,
                    SmbConstants.O_WRONLY or SmbConstants.O_CREAT or SmbConstants.O_EXCL,
                    SmbConstants.FILE_WRITE_DATA,
                    SmbConstants.FILE_NO_SHARE,
                )
            } returns output
            every { file.openInputStream() } answers { ByteArrayInputStream(output.toByteArray()) }
            probe = NasDiagnosticProbe(client)
        }

        fun run(write: Boolean = false) = probe.check("nas.test", "smb://host/share", "user", "secret", "base", write)

        expect("interrupted owned write still cleans up and propagates cancellation") {
            every { file.openInputStream() } throws IOException("wrapped", InterruptedException())
            try {
                io.kotest.assertions.throwables
                    .shouldThrow<InterruptedException> { run(true) }
                verify(exactly = 1) { file.delete() }
            } finally {
                Thread.interrupted()
            }
        }
        expect("default checks directory and listing but never writes") {
            val checks = run()
            checks.single { it.id.endsWith(".read") }.status shouldBe DiagnosticStatus.PASS
            checks.single { it.id.endsWith(".write") }.status shouldBe DiagnosticStatus.UNCHECKED
            verify(exactly = 0) { directory.resolve(any<String>()) }
            verify {
                root.close()
                directory.close()
            }
        }
        expect("missing directory reports actionable failure without creating it") {
            every { directory.exists() } returns false
            val checks = run(true)
            checks.first().status shouldBe DiagnosticStatus.FAIL
            checks.last().status shouldBe DiagnosticStatus.UNCHECKED
            verify(exactly = 0) { directory.mkdir() }
        }
        expect("denied listing is a safe failure and no write is attempted") {
            every { directory.children() } throws IOException("password=secret")
            val checks = run(true)
            checks.first().status shouldBe DiagnosticStatus.FAIL
            checks.toString() shouldNotContain "secret"
            verify(exactly = 0) { file.openOutputStream(any(), any(), any(), any()) }
        }
        expect("write probe uses an isolated name, verifies bytes and deletes its own file") {
            val name = slot<String>()
            every { directory.resolve(capture(name)) } returns file
            val checks = run(true)
            checks.all { it.status == DiagnosticStatus.PASS } shouldBe true
            name.captured shouldContain ".tsvideos-doctor-"
            name.captured.endsWith(".tmp") shouldBe true
            output.toString(Charsets.UTF_8) shouldBe "tsvideos-doctor\n"
            verify(exactly = 1) {
                file.delete()
            }
        }
        expect("write failure still removes the probe") {
            every { file.openOutputStream(any(), any(), any(), any()) } returns
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
            every { file.openOutputStream(any(), any(), any(), any()) } throws IOException("already exists")
            run(true).single { it.id.endsWith(".write") }.status shouldBe DiagnosticStatus.FAIL
            verify(exactly = 0) { file.delete() }
        }
    })
