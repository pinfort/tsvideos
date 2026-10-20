package me.pinfort.tsvideos.storage.samba

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jcifs.SmbConstants
import jcifs.SmbResource
import jcifs.smb.SmbFile
import me.pinfort.tsvideos.storage.api.NasComponent
import me.pinfort.tsvideos.storage.api.NasStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class NasMoveSafetyTest :
    ExpectSpec({
        val client = mockk<SambaClient>()
        val video = mockk<SmbFile>(relaxed = true)
        val original = mockk<SmbFile>(relaxed = true)
        val source = mockk<SmbResource>(relaxed = true)
        val target = mockk<SmbResource>(relaxed = true)
        val old = "base/a/alpha/file.mp4"
        val new = "base/b/beta/file.mp4"
        lateinit var output: ByteArrayOutputStream
        lateinit var nas: NasComponent
        beforeTest {
            io.mockk.clearMocks(video, original, source, target)
            every { client.videoStoreNas() } returns video
            every { client.originalStoreNas() } returns original
            every { video.resolve(old) } returns source
            every { video.resolve(new) } returns target
            every { source.exists() } returns true
            every { source.isFile } returns true
            every { source.length() } returns 3
            every { source.lastModified() } returns 10
            every { source.openInputStream() } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
            output = ByteArrayOutputStream()
            every { target.openOutputStream(true, any(), any(), any()) } returns output
            every { target.length() } answers { output.size().toLong() }
            every { target.openInputStream() } answers { ByteArrayInputStream(output.toByteArray()) }
            nas = SambaNasComponent(client, mockk(relaxed = true))
        }
        expect("exclusive copy and readback complete before source deletion") {
            nas.moveResource(old, new) shouldBe NasStore.VIDEO_STORE_NAS
            output.toByteArray().toList() shouldBe listOf<Byte>(1, 2, 3)
            verify(exactly = 1) {
                target.openOutputStream(
                    true,
                    SmbConstants.O_WRONLY or SmbConstants.O_CREAT or SmbConstants.O_EXCL,
                    SmbConstants.FILE_WRITE_DATA,
                    SmbConstants.FILE_NO_SHARE,
                )
                source.delete()
            }
            verify(exactly = 0) { source.copyTo(any()) }
        }
        expect("existing target or exclusive create failure never deletes source") {
            every { target.openOutputStream(true, any(), any(), any()) } throws IllegalStateException("exists")
            shouldThrow<IllegalStateException> { nas.moveResource(old, new) }
            verify(exactly = 0) {
                source.delete()
                target.delete()
            }
        }
        expect("truncated copy checksum mismatch and changed source retain source") {
            every { target.length() } returns 2
            shouldThrow<IllegalStateException> { nas.moveResource(old, new) }
            every { target.length() } returns 3
            every { source.openInputStream() } answers { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
            every { target.openInputStream() } returns ByteArrayInputStream(byteArrayOf(3, 2, 1))
            shouldThrow<IllegalStateException> { nas.moveResource(old, new) }
            every { target.openInputStream() } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
            every { source.lastModified() } returnsMany listOf(10, 20)
            shouldThrow<IllegalStateException> { nas.moveResource(old, new) }
            verify(exactly = 0) { source.delete() }
            // Size and checksum failures remove the exclusively created copy; a changed source keeps it.
            verify(exactly = 2) { target.delete() }
        }
        expect("NAS boundary rejects traversal, streams and control characters before opening anything") {
            for (unsafe in listOf(
                "../file",
                "base/a/../file",
                "/base/a/alpha/file",
                "base//alpha/file",
                "base/a/alpha/file:stream",
                "base/a/alpha/a\u0000b",
            )) {
                shouldThrow<IllegalArgumentException> { nas.moveResource(unsafe, new) }
                shouldThrow<IllegalArgumentException> { nas.deleteResource(unsafe) }
                shouldThrow<IllegalArgumentException> { nas.moveResource(old, unsafe) }
            }
            verify(exactly = 0) {
                video.resolve(any())
                source.delete()
            }
        }
        expect("registered names with URL-special characters are deleted verbatim") {
            for (name in listOf("base/a/alpha/アニメ #01 100%.mp4", "base/a/alpha/a%2e%2e%2fb.mp4", "base/a/alpha/Dr.")) {
                val file = mockk<SmbResource>(relaxed = true)
                every { video.resolve(name) } returns file
                every { file.exists() } returns true
                every { file.isFile } returns true
                nas.deleteResource(name) shouldBe NasStore.VIDEO_STORE_NAS
                verify(exactly = 1) { file.delete() }
            }
        }
        expect("case-only renames are refused because SMB treats them as the same file") {
            shouldThrow<IllegalArgumentException> { nas.moveResource(old, old.uppercase()) }
            verify(exactly = 0) {
                video.resolve(any())
                source.delete()
            }
        }
        expect("directory deletion and movement are refused without recursive effects") {
            every { source.isFile } returns false
            shouldThrow<IllegalStateException> { nas.deleteResource(old) }
            shouldThrow<IllegalStateException> { nas.moveResource(old, new) }
            verify(exactly = 0) {
                source.delete()
                source.openInputStream()
            }
        }
        expect("read failure retains source and removes only its own partial destination") {
            every { source.openInputStream() } throws IllegalStateException("disconnected")
            shouldThrow<IllegalStateException> { nas.moveResource(old, new) }
            verify(exactly = 0) { source.delete() }
            verify(exactly = 1) { target.delete() }
        }
        expect("destination is kept when the source cannot be confirmed intact") {
            every { source.openInputStream() } throws IllegalStateException("disconnected")
            every { source.length() } returnsMany listOf(3L, 0L)
            shouldThrow<IllegalStateException> { nas.moveResource(old, new) }
            verify(exactly = 0) {
                source.delete()
                target.delete()
            }
        }
        expect("a failed source delete removes the verified copy only while the source still exists") {
            every { source.delete() } throws IllegalStateException("locked")
            shouldThrow<IllegalStateException> { nas.moveResource(old, new) }
            verify(exactly = 1) { target.delete() }
            every { source.openInputStream() } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
            output = ByteArrayOutputStream()
            every { target.openOutputStream(true, any(), any(), any()) } returns output
            // Source vanished during the failed delete: the destination is now the only copy.
            every { source.exists() } returnsMany listOf(true, true, false)
            shouldThrow<IllegalStateException> { nas.moveResource(old, new) }
            verify(exactly = 1) { target.delete() }
        }
    })
