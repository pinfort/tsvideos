package me.pinfort.tsvideos.storage.samba

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import jcifs.SmbResource
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import me.pinfort.tsvideos.storage.api.NasAccessException
import me.pinfort.tsvideos.storage.api.NasAuthenticationException
import me.pinfort.tsvideos.storage.api.NasComponent
import me.pinfort.tsvideos.storage.api.NasStore
import me.pinfort.tsvideos.storage.api.VerifiedUploadNas
import java.io.File
import java.io.OutputStream
import java.net.SocketTimeoutException

/**
 * Boundary translation over mocked jcifs handles; SMB semantics themselves are covered by VerifiedUploadNasTest.
 * Retry classification of the translated types is asserted by the callers (processing's ProcessingStorageCallerTest).
 */
class VerifiedUploadNasFailureTest :
    ExpectSpec({
        lateinit var root: SmbFile
        lateinit var temporary: SmbResource
        lateinit var target: SmbResource
        lateinit var nas: VerifiedUploadNas
        lateinit var source: File

        beforeTest {
            val samba = mockk<SambaClient>()
            val directories = mockk<NasComponent>()
            root = mockk(relaxed = true)
            temporary = mockk(relaxed = true)
            target = mockk(relaxed = true)
            every { samba.videoStoreNas() } returns root
            every { directories.createDirectories(any(), any()) } just Runs
            every { root.resolve("a/b/file.mp4.upload-1") } returns temporary
            every { root.resolve("a/b/file.mp4") } returns target
            nas = SambaVerifiedUploadNas(samba, directories)
            source = File.createTempFile("verified-upload-failure", ".tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        }

        afterTest { source.delete() }

        fun transfer(
            onOwned: () -> Unit = {},
            progress: (Long, Long) -> Unit = { _, _ -> },
        ) = nas.transfer(NasStore.VIDEO_STORE_NAS, "a/b/file.mp4.upload-1", source, onOwned, progress)

        context("transfer") {
            expect("a failed exclusive open is NasAccessException and never grants ownership") {
                val failure = SmbException("smb://nas/share already exists")
                every { temporary.openOutputStream(any(), any(), any(), any()) } throws failure
                var owned = false

                val thrown = shouldThrow<NasAccessException> { transfer(onOwned = { owned = true }) }

                thrown.cause shouldBeSameInstanceAs failure
                owned shouldBe false
                verify { temporary.close() }
                verify { root.close() }
            }

            expect("a write timeout is translated, keeps its close failure as suppressed evidence and its timeout cause") {
                val writeFailure = SmbException("write failed", SocketTimeoutException())
                val closeFailure = SmbException("close failed")
                val output = mockk<OutputStream>()
                every { output.write(any<ByteArray>(), any(), any()) } throws writeFailure
                every { output.close() } throws closeFailure
                every { temporary.openOutputStream(any(), any(), any(), any()) } returns output

                val thrown = shouldThrow<NasAccessException> { transfer() }

                thrown.cause shouldBeSameInstanceAs writeFailure
                writeFailure.suppressed.toList() shouldContainExactly listOf(closeFailure)
                thrown.causeChain().any { it is SocketTimeoutException } shouldBe true
            }

            expect("an ownership callback failure propagates unchanged, with the SMB close failure suppressed on it") {
                val callbackFailure = IllegalStateException("claim lost")
                val closeFailure = SmbException("close failed")
                val output = mockk<OutputStream>()
                every { output.close() } throws closeFailure
                every { temporary.openOutputStream(any(), any(), any(), any()) } returns output

                val thrown = shouldThrow<IllegalStateException> { transfer(onOwned = { throw callbackFailure }) }

                thrown shouldBeSameInstanceAs callbackFailure
                thrown.suppressed.toList() shouldContainExactly listOf(closeFailure)
            }

            expect("a progress callback failure propagates unchanged") {
                val callbackFailure = IllegalStateException("cancelled")
                every { temporary.openOutputStream(any(), any(), any(), any()) } returns OutputStream.nullOutputStream()

                shouldThrow<IllegalStateException> { transfer(progress = { _, _ -> throw callbackFailure }) } shouldBeSameInstanceAs
                    callbackFailure
            }
        }

        context("finalize") {
            expect("a rejected rename login is NasAuthenticationException even with a timeout in its chain") {
                every { target.exists() } returns false
                val denied = mockk<SmbAuthException>(relaxed = true) { every { cause } returns SocketTimeoutException() }
                every { temporary.renameTo(target, false) } throws denied

                val thrown =
                    shouldThrow<NasAuthenticationException> {
                        nas.finalize(NasStore.VIDEO_STORE_NAS, "a/b/file.mp4.upload-1", "a/b/file.mp4")
                    }

                thrown.cause shouldBeSameInstanceAs denied
            }

            expect("an existing final destination is still a plain state failure") {
                every { target.exists() } returns true

                shouldThrow<IllegalStateException> {
                    nas.finalize(NasStore.VIDEO_STORE_NAS, "a/b/file.mp4.upload-1", "a/b/file.mp4")
                }
                verify(exactly = 0) { temporary.renameTo(any(), any()) }
            }
        }

        context("delete and inspection") {
            expect("a failed delete is NasAccessException") {
                every { temporary.exists() } returns true
                every { temporary.delete() } throws SmbException("delete failed")

                shouldThrow<NasAccessException> { nas.delete(NasStore.VIDEO_STORE_NAS, "a/b/file.mp4.upload-1") }
            }

            expect("a failed checksum stream read is NasAccessException") {
                every { target.openInputStream() } throws SmbException("read failed")

                shouldThrow<NasAccessException> { nas.sha256(NasStore.VIDEO_STORE_NAS, "a/b/file.mp4") }
            }

            expect("a missing artifact is still a plain state failure, not a storage exception") {
                every { target.exists() } returns false

                shouldThrow<IllegalStateException> { nas.size(NasStore.VIDEO_STORE_NAS, "a/b/file.mp4") }
            }
        }
    })
