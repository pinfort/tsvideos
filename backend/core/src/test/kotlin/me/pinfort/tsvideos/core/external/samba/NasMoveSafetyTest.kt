package me.pinfort.tsvideos.core.external.samba

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jcifs.SmbConstants
import jcifs.SmbResource
import jcifs.smb.SmbFile
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
            every { source.length() } returns 3
            every { source.lastModified() } returns 10
            every { source.openInputStream() } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
            output = ByteArrayOutputStream()
            every { target.openOutputStream(true, any(), any(), any()) } returns output
            every { target.length() } answers { output.size().toLong() }
            every { target.openInputStream() } answers { ByteArrayInputStream(output.toByteArray()) }
            nas = NasComponent(client, mockk(relaxed = true))
        }
        expect("exclusive copy and readback complete before source deletion") {
            nas.moveResource(old, new) shouldBe SambaClient.NasType.VIDEO_STORE_NAS
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
        }
        expect("NAS boundary rejects URI delimiters and encoded traversal before opening anything") {
            for (unsafe in listOf("base/a/alpha/%2e%2e%2fother", "base/a/alpha/file#fragment", "base/a/alpha/file?query", "../file")) {
                shouldThrow<IllegalArgumentException> { nas.moveResource(unsafe, new) }
                shouldThrow<IllegalArgumentException> { nas.moveResource(old, unsafe) }
            }
            verify(exactly = 0) {
                video.resolve(any())
                source.delete()
            }
        }
        expect("read failure retains source and does not delete an uncertain destination") {
            every { source.openInputStream() } throws IllegalStateException("disconnected")
            shouldThrow<IllegalStateException> { nas.moveResource(old, new) }
            verify(exactly = 0) {
                source.delete()
                target.delete()
            }
        }
    })
