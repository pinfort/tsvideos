package me.pinfort.tsvideos.storage.samba

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jcifs.CloseableIterator
import jcifs.RuntimeCIFSException
import jcifs.SmbConstants
import jcifs.SmbResource
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import me.pinfort.tsvideos.storage.api.NasAccessException
import me.pinfort.tsvideos.storage.api.NasAuthenticationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** The diagnostic SMB adapter over mocked jcifs resources: flags, close ownership and failure translation. */
class SambaDiagnosticNasClientTest :
    ExpectSpec({
        lateinit var root: SmbResource
        lateinit var directory: SmbResource
        lateinit var file: SmbResource
        lateinit var children: CloseableIterator<SmbResource>
        lateinit var output: ByteArrayOutputStream
        var contextClosed = 0
        beforeTest {
            root = mockk(relaxed = true)
            directory = mockk(relaxed = true)
            file = mockk(relaxed = true)
            children = mockk(relaxed = true)
            output = ByteArrayOutputStream()
            contextClosed = 0
            every { root.resolve("base/") } returns directory
            every { directory.exists() } returns true
            every { directory.isDirectory } returns true
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
        }

        fun connection() = SambaDiagnosticNasClient.connection(root) { contextClosed++ }

        expect("a base directory is resolved under the root and closed with its view; the root closes with the connection") {
            connection().use { connection ->
                connection.directory("/base/").use { view ->
                    view.isDirectory() shouldBe true
                    view.listFirstEntry()
                }
                verify(exactly = 1) { directory.close() }
                verify(exactly = 0) { root.close() }
            }
            verify(exactly = 1) {
                children.close()
                root.close()
            }
            contextClosed shouldBe 1
        }
        expect("a blank base directory views the share root without closing it before the connection") {
            every { root.exists() } returns true
            every { root.isDirectory } returns true
            every { root.children() } returns children
            connection().use { connection ->
                connection.directory("").use { view ->
                    view.isDirectory() shouldBe true
                    view.listFirstEntry()
                }
                verify(exactly = 0) { root.close() }
            }
            verify(exactly = 0) { root.resolve(any<String>()) }
            verify(exactly = 1) { root.close() }
        }
        expect("a missing path is not a directory") {
            every { directory.exists() } returns false
            connection().use { it.directory("base").use { view -> view.isDirectory() shouldBe false } }
            verify(exactly = 0) { directory.mkdir() }
        }
        expect("listing fetches at most one entry and closes it") {
            val entry = mockk<SmbResource>(relaxed = true)
            every { children.hasNext() } returns true
            every { children.next() } returns entry
            connection().use { it.directory("base").use { view -> view.listFirstEntry() } }
            verify(exactly = 1) {
                children.next()
                entry.close()
            }
        }
        expect("exclusive creation uses O_EXCL and the handle reads back and deletes the same file") {
            connection().use { connection ->
                connection.directory("base").use { view ->
                    view.file("probe.tmp").use { handle ->
                        handle.createExclusive().use { it.write("tsvideos".toByteArray()) }
                        handle.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) shouldBe "tsvideos" }
                        handle.delete()
                    }
                }
            }
            verify(exactly = 1) {
                directory.resolve("probe.tmp")
                file.openOutputStream(
                    true,
                    SmbConstants.O_WRONLY or SmbConstants.O_CREAT or SmbConstants.O_EXCL,
                    SmbConstants.FILE_WRITE_DATA,
                    SmbConstants.FILE_NO_SHARE,
                )
                file.delete()
                file.close()
            }
        }
        expect("diagnostic handles and streams surface Samba failures as storage exceptions") {
            val connection = connection()
            val directoryView = connection.directory("base")
            every { directory.children() } throws RuntimeCIFSException("smb://host/share listing denied")
            shouldThrow<NasAccessException> { directoryView.listFirstEntry() }.message shouldNotContain "smb://"

            val handle = directoryView.file("probe.tmp")
            every { file.openOutputStream(any(), any(), any(), any()) } throws SmbException("exists")
            shouldThrow<NasAccessException> { handle.createExclusive() }

            every { file.openOutputStream(any(), any(), any(), any()) } returns
                object : OutputStream() {
                    override fun write(value: Int) = throw SmbException("write failed")
                }
            shouldThrow<NasAccessException> { handle.createExclusive().write(1) }

            every { file.delete() } throws mockk<SmbAuthException>(relaxed = true) { every { cause } returns null }
            shouldThrow<NasAuthenticationException> { handle.delete() }

            every { file.openInputStream() } throws InterruptedException()
            shouldThrow<InterruptedException> { handle.openInputStream() }
        }
        expect("a failed root close still closes the context and is a storage exception") {
            every { root.close() } throws SmbException("close failed")
            shouldThrow<NasAccessException> { connection().close() }
            contextClosed shouldBe 1
        }
    })
