package me.pinfort.tsvideos.storage.samba

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jcifs.SmbResource
import me.pinfort.tsvideos.storage.api.NasFileVerifier
import me.pinfort.tsvideos.storage.api.NasFileVerifier.Status
import java.io.IOException

class NasFileVerifierTest :
    ExpectSpec({
        lateinit var client: SambaClient
        lateinit var video: SmbResource
        lateinit var original: SmbResource
        lateinit var videoFile: SmbResource
        lateinit var originalFile: SmbResource
        lateinit var verifier: NasFileVerifier
        beforeTest {
            client = mockk()
            video = mockk(relaxed = true)
            original = mockk(relaxed = true)
            videoFile = mockk(relaxed = true)
            originalFile = mockk(relaxed = true)
            every { client.videoStoreNas() } returns video
            every { client.originalStoreNas() } returns original
            every { video.resolve("base/path/file.mp4") } returns videoFile
            every { original.resolve("base/path/file.mp4") } returns originalFile
            every { videoFile.isFile } returns true
            every { originalFile.isFile } returns true
            verifier = SambaNasFileVerifier(client)
        }
        expect("verifies size and normalizes separators without repeating base directory") {
            every { videoFile.exists() } returns true
            every { videoFile.length() } returns 12
            verifier.verify("base\\path\\file.mp4", 12).status shouldBe Status.VERIFIED
            verify(exactly = 0) { client.originalStoreNas() }
            verify {
                videoFile.close()
                video.close()
            }
        }
        expect("finds original-store artifacts and reports a mismatch") {
            every { videoFile.exists() } returns false
            every { originalFile.exists() } returns true
            every { originalFile.length() } returns 11
            val result = verifier.verify("base/path/file.mp4", 12)
            result.status shouldBe Status.SIZE_MISMATCH
            result.actualBytes shouldBe 11
        }
        expect("missing requires successful absence checks on both stores") {
            every { videoFile.exists() } returns false
            every { originalFile.exists() } returns false
            verifier.verify("base/path/file.mp4", 12).status shouldBe Status.MISSING
        }
        expect("authentication or network failure is inaccessible and sanitized") {
            every { videoFile.exists() } throws IOException("smb://secret:password@host")
            val result = verifier.verify("base/path/file.mp4", 12)
            result.status shouldBe Status.INACCESSIBLE
            result.reason shouldBe "NAS metadata could not be read"
            verify(exactly = 0) { client.originalStoreNas() }
        }
        expect("failure reading size and inaccessible fallback are never missing") {
            every { videoFile.exists() } returns false
            every { originalFile.exists() } returns true
            every { originalFile.length() } throws IOException("disconnected")
            verifier.verify("base/path/file.mp4", 12).status shouldBe Status.INACCESSIBLE
        }
        expect("a session opens each store once across files and closes it at the end") {
            every { video.resolve("base/other.mp4") } returns videoFile
            every { videoFile.exists() } returns true
            every { videoFile.length() } returns 12
            verifier.session { session ->
                session.verify("base/path/file.mp4", 12).status shouldBe Status.VERIFIED
                session.verify("base/other.mp4", 12).status shouldBe Status.VERIFIED
                verify(exactly = 0) { video.close() }
            }
            verify(exactly = 1) {
                client.videoStoreNas()
                video.close()
            }
            verify(exactly = 0) { client.originalStoreNas() }
        }
        expect("a store that fails to open is not retried within a session") {
            every { client.videoStoreNas() } throws IOException("smb://secret:password@host")
            verifier.session { session ->
                repeat(3) { session.verify("base/path/file.mp4", 12).status shouldBe Status.INACCESSIBLE }
            }
            verify(exactly = 1) { client.videoStoreNas() }
            verify(exactly = 0) { client.originalStoreNas() }
        }
        expect("names with URL-special characters reach resolve unchanged, as upload stores them") {
            // VerifiedUploadNas and NasComponent pass the stored path to resolve as-is, so verify must too.
            val name = "base/アニメ：第2話 #01 100% 本当?.mp4" // full-width colon is a normal title character, not ':'
            val file = mockk<SmbResource>(relaxed = true)
            every { video.resolve(name) } returns file
            every { file.exists() } returns true
            every { file.isFile } returns true
            every { file.length() } returns 12
            verifier.verify(name, 12).status shouldBe Status.VERIFIED
            verify(exactly = 1) { video.resolve(name) }
        }
        expect("directories and invalid metadata remain unchecked") {
            every { videoFile.exists() } returns true
            every { videoFile.isFile } returns false
            verifier.verify("base/path/file.mp4", 12).status shouldBe Status.UNCHECKED
            listOf("", "../escape", "/absolute", "C:\\local", "smb://host/file", "base/./file.mp4", "base//file.mp4", "base/dir/").forEach {
                verifier.verify(it, 12).status shouldBe Status.UNCHECKED
            }
            verifier.verify("base/path/file.mp4", -1).status shouldBe Status.UNCHECKED
        }
    })
