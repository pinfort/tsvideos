package me.pinfort.tsvideos.core.external.samba

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import jcifs.SmbResource
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import me.pinfort.tsvideos.core.component.TransientProcessingFailure
import me.pinfort.tsvideos.core.storage.NasAccessException
import me.pinfort.tsvideos.core.storage.NasAuthenticationException
import me.pinfort.tsvideos.core.storage.NasStore
import org.slf4j.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

class NasComponentTest :
    ExpectSpec({
        lateinit var sambaClient: SambaClient
        lateinit var logger: Logger
        lateinit var videoStoreNas: SmbFile
        lateinit var originalStoreNas: SmbFile
        lateinit var nasComponent: NasComponent

        beforeTest {
            clearAllMocks()
            sambaClient = mockk()
            logger = mockk()
            videoStoreNas = mockk()
            originalStoreNas = mockk()
            every { sambaClient.videoStoreNas() } returns videoStoreNas
            every { sambaClient.originalStoreNas() } returns originalStoreNas
            nasComponent = NasComponent(sambaClient, logger)
        }

        context("resourceExists") {
            expect("true when the file is on the video store nas") {
                val resource = mockk<SmbResource>()
                every { videoStoreNas.resolve("a/bucket/file.mp4") } returns resource
                every { resource.exists() } returns true

                nasComponent.resourceExists("a\\bucket\\file.mp4") shouldBe true

                verify(exactly = 0) { originalStoreNas.resolve(any()) }
            }

            expect("true when the file is only on the original store nas") {
                val videoResource = mockk<SmbResource>()
                val originalResource = mockk<SmbResource>()
                every { videoStoreNas.resolve("a/bucket/file.gz") } returns videoResource
                every { videoResource.exists() } returns false
                every { originalStoreNas.resolve("a/bucket/file.gz") } returns originalResource
                every { originalResource.exists() } returns true

                nasComponent.resourceExists("a/bucket/file.gz") shouldBe true
            }

            expect("false when the file is on neither nas") {
                val videoResource = mockk<SmbResource>()
                val originalResource = mockk<SmbResource>()
                every { videoStoreNas.resolve("a/bucket/file.mp4") } returns videoResource
                every { videoResource.exists() } returns false
                every { originalStoreNas.resolve("a/bucket/file.mp4") } returns originalResource
                every { originalResource.exists() } returns false

                nasComponent.resourceExists("a/bucket/file.mp4") shouldBe false
            }
        }

        context("uploadResource") {
            expect("skip when target already exists") {
                val resource = mockk<SmbResource>()
                every { originalStoreNas.resolve(any()) } returns resource
                every { resource.exists() } returns true
                every { logger.info(any()) } just Runs

                val localFile = File.createTempFile("nas-upload-test", ".tmp")

                val result = nasComponent.uploadResource(localFile, "a/bucket/file.gz", NasStore.ORIGINAL_STORE_NAS)

                result shouldBe false
                verify(exactly = 0) { resource.openOutputStream() }
                verify(exactly = 0) { resource.mkdir() }
            }

            expect("uploads when target does not exist") {
                val resource = mockk<SmbResource>()
                val output = ByteArrayOutputStream()
                every { originalStoreNas.resolve(any()) } returns resource
                every { resource.exists() } returns false
                every { resource.mkdir() } just Runs
                every { resource.openOutputStream() } returns output
                every { logger.info(any()) } just Runs

                val localFile = File.createTempFile("nas-upload-test", ".tmp")
                localFile.writeBytes(byteArrayOf(1, 2, 3))

                val result = nasComponent.uploadResource(localFile, "a/bucket/file.gz", NasStore.ORIGINAL_STORE_NAS)

                result shouldBe true
                output.toByteArray() shouldBe byteArrayOf(1, 2, 3)
                verify { resource.mkdir() }
                verify { resource.openOutputStream() }
            }

            expect("uploads to video store nas") {
                val resource = mockk<SmbResource>()
                val output = ByteArrayOutputStream()
                every { videoStoreNas.resolve(any()) } returns resource
                every { resource.exists() } returns false
                every { resource.mkdir() } just Runs
                every { resource.openOutputStream() } returns output
                every { logger.info(any()) } just Runs

                val localFile = File.createTempFile("nas-upload-test", ".tmp")

                val result = nasComponent.uploadResource(localFile, "a/bucket/file.mp4", NasStore.VIDEO_STORE_NAS)

                result shouldBe true
                verify { videoStoreNas.resolve("a/bucket/file.mp4") }
            }
        }

        context("openForRead") {
            expect("opens the file on a fresh root of the requested store only") {
                val freshRoot = mockk<SmbFile>()
                val resource = mockk<SmbResource>()
                every { sambaClient.root(NasStore.VIDEO_STORE_NAS) } returns freshRoot
                every { freshRoot.resolve("a/bucket/file.mp4") } returns resource
                every { resource.length() } returns 1024L

                val opened = nasComponent.openForRead(NasStore.VIDEO_STORE_NAS, "a\\bucket\\file.mp4")

                opened.contentLength() shouldBe 1024L
                verify(exactly = 0) { sambaClient.root(NasStore.ORIGINAL_STORE_NAS) }
                verify(exactly = 0) { videoStoreNas.resolve(any()) }
                verify(exactly = 0) { originalStoreNas.resolve(any()) }
            }

            expect("translates a Samba failure into NasAccessException without the server URL") {
                val freshRoot = mockk<SmbFile>()
                val resource = mockk<SmbResource>()
                val failure = SmbException("smb://user:secret@nas/share/a/bucket/file.mp4 not found")
                every { sambaClient.root(NasStore.VIDEO_STORE_NAS) } returns freshRoot
                every { freshRoot.resolve(any()) } returns resource
                every { resource.length() } throws failure

                val thrown = shouldThrow<NasAccessException> { nasComponent.openForRead(NasStore.VIDEO_STORE_NAS, "a/bucket/file.mp4") }

                thrown.cause shouldBe failure
                thrown.message shouldNotContain "smb://"
                thrown.message shouldNotContain "secret"
            }

            expect("does not translate failures that are not Samba failures") {
                val freshRoot = mockk<SmbFile>()
                every { sambaClient.root(NasStore.VIDEO_STORE_NAS) } returns freshRoot
                every { freshRoot.resolve(any()) } throws IllegalStateException("boom")

                shouldThrow<IllegalStateException> { nasComponent.openForRead(NasStore.VIDEO_STORE_NAS, "a/bucket/file.mp4") }
            }

            expect("the returned resource opens and reads with storage exceptions, not jcifs ones") {
                val freshRoot = mockk<SmbFile>()
                val resource = mockk<SmbResource>()
                val readFailure = SmbException("smb://nas/share read failed")
                every { sambaClient.root(NasStore.VIDEO_STORE_NAS) } returns freshRoot
                every { freshRoot.resolve(any()) } returns resource
                every { resource.length() } returns 3L
                every { resource.openInputStream() } returns
                    object : InputStream() {
                        override fun read(): Int = throw readFailure

                        override fun read(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ): Int = throw readFailure
                    }
                val opened = nasComponent.openForRead(NasStore.VIDEO_STORE_NAS, "a/bucket/file.mp4")

                shouldThrow<NasAccessException> { opened.inputStream.use { it.read() } }.cause shouldBe readFailure

                every { resource.openInputStream() } throws mockk<SmbAuthException>(relaxed = true) { every { cause } returns null }
                shouldThrow<NasAuthenticationException> { opened.inputStream }
            }
        }

        context("deleteResource") {
            expect("a Samba delete failure is NasAccessException") {
                val resource = mockk<SmbResource>()
                val failure = SmbException("delete failed")
                every { videoStoreNas.resolve("a/bucket/file.mp4") } returns resource
                every { resource.exists() } returns true
                every { resource.isFile } returns true
                every { resource.delete() } throws failure

                shouldThrow<NasAccessException> { nasComponent.deleteResource("a/bucket/file.mp4") }.cause shouldBe failure
            }

            expect("validation, directory refusal and a missing file are not translated") {
                val directory = mockk<SmbResource>()
                every { videoStoreNas.resolve("a/bucket") } returns directory
                every { directory.exists() } returns true
                every { directory.isFile } returns false
                val missing = mockk<SmbResource>()
                every { videoStoreNas.resolve("a/bucket/none.mp4") } returns missing
                every { originalStoreNas.resolve("a/bucket/none.mp4") } returns missing
                every { missing.exists() } returns false

                shouldThrow<IllegalArgumentException> { nasComponent.deleteResource("../escape.mp4") }
                shouldThrow<IllegalStateException> { nasComponent.deleteResource("a/bucket") }
                shouldThrow<Exception> { nasComponent.deleteResource("a/bucket/none.mp4") }.message shouldBe
                    "File not found, path=a/bucket/none.mp4"
                verify(exactly = 0) { directory.delete() }
            }
        }

        context("moveResource") {
            lateinit var source: SmbResource
            lateinit var target: SmbResource

            beforeTest {
                every { logger.info(any()) } just Runs
                every { logger.error(any()) } just Runs
                every { logger.error(any(), any<Throwable>()) } just Runs
                val directory = mockk<SmbResource>()
                every { directory.exists() } returns true
                every { videoStoreNas.resolve(any()) } returns directory
                source = mockk(relaxed = true)
                target = mockk(relaxed = true)
                every { videoStoreNas.resolve("a/old/file.mp4") } returns source
                every { videoStoreNas.resolve("a/new/file.mp4") } returns target
                every { source.exists() } returns true
                every { source.isFile } returns true
                every { source.length() } returns 3L
                every { source.lastModified() } returns 100L
                every { source.openInputStream() } answers { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
            }

            expect("a failed exclusive create is NasAccessException and leaves an unowned destination alone") {
                val failure = SmbException("already exists")
                every { target.openOutputStream(any(), any(), any(), any()) } throws failure

                shouldThrow<NasAccessException> { nasComponent.moveResource("a/old/file.mp4", "a/new/file.mp4") }.cause shouldBe failure

                verify(exactly = 0) { target.delete() }
                verify(exactly = 0) { source.delete() }
            }

            expect("a copy failure after creation still discards the owned partial copy before translation") {
                val failure = SmbException("write failed", SocketTimeoutException())
                every { target.openOutputStream(any(), any(), any(), any()) } returns
                    object : OutputStream() {
                        override fun write(b: Int) = throw failure

                        override fun write(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ) = throw failure
                    }

                val thrown = shouldThrow<NasAccessException> { nasComponent.moveResource("a/old/file.mp4", "a/new/file.mp4") }

                thrown.cause shouldBe failure
                TransientProcessingFailure.reason(thrown) shouldBe "NETWORK_UNAVAILABLE"
                verify(exactly = 1) { target.delete() }
                verify(exactly = 0) { source.delete() }
            }

            expect("a failed source delete after a verified copy is NasAccessException") {
                val written = ByteArrayOutputStream()
                every { target.openOutputStream(any(), any(), any(), any()) } returns written
                every { target.length() } returns 3L
                every { target.openInputStream() } answers { ByteArrayInputStream(written.toByteArray()) }
                every { source.delete() } throws SmbException("delete failed")

                shouldThrow<NasAccessException> { nasComponent.moveResource("a/old/file.mp4", "a/new/file.mp4") }
            }

            expect("a size mismatch is still a plain state failure") {
                every { target.openOutputStream(any(), any(), any(), any()) } returns ByteArrayOutputStream()
                every { target.length() } returns 0L

                shouldThrow<IllegalStateException> { nasComponent.moveResource("a/old/file.mp4", "a/new/file.mp4") }
            }
        }

        context("uploadResource failures") {
            expect("a rejected login is NasAuthenticationException; a progress callback failure is unchanged") {
                val resource = mockk<SmbResource>()
                every { logger.info(any()) } just Runs
                every { originalStoreNas.resolve(any()) } returns resource
                every { resource.exists() } returns false
                every { resource.mkdir() } just Runs
                every { resource.openOutputStream() } throws mockk<SmbAuthException>(relaxed = true) { every { cause } returns null }
                val localFile = File.createTempFile("nas-upload-test", ".tmp")

                shouldThrow<NasAuthenticationException> {
                    nasComponent.uploadResource(localFile, "a/bucket/file.gz", NasStore.ORIGINAL_STORE_NAS)
                }

                val callbackFailure = IllegalStateException("cancelled")
                every { resource.openOutputStream() } returns ByteArrayOutputStream()
                shouldThrow<IllegalStateException> {
                    nasComponent.uploadResource(localFile, "a/bucket/file.gz", NasStore.ORIGINAL_STORE_NAS) { _, _ ->
                        throw callbackFailure
                    }
                } shouldBe callbackFailure
            }

            expect("a failed directory creation is NasAccessException") {
                val resource = mockk<SmbResource>()
                every { logger.info(any()) } just Runs
                every { videoStoreNas.resolve(any()) } returns resource
                every { resource.exists() } returns false
                every { resource.mkdir() } throws SmbException("mkdir failed")

                shouldThrow<NasAccessException> { nasComponent.createDirectories("a/bucket/file.mp4", NasStore.VIDEO_STORE_NAS) }
            }
        }
    })
