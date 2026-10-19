package me.pinfort.tsvideos.core.external.samba

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import jcifs.RuntimeCIFSException
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.component.TransientProcessingFailure
import me.pinfort.tsvideos.core.storage.NasAccessException
import me.pinfort.tsvideos.core.storage.NasAuthenticationException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

class SambaFailuresTest :
    ExpectSpec({
        fun authFailure(): SmbAuthException = mockk(relaxed = true) { every { cause } returns null }

        context("sambaOperation") {
            expect("a network failure becomes NasAccessException and stays retryable through its cause") {
                val failure = SmbException("smb://user:secret@nas/share timed out", SocketTimeoutException())

                val thrown = shouldThrow<NasAccessException> { sambaOperation("upload") { throw failure } }

                thrown.cause shouldBeSameInstanceAs failure
                thrown.message shouldNotContain "smb://"
                thrown.message shouldNotContain "secret"
                TransientProcessingFailure.reason(thrown) shouldBe "NETWORK_UNAVAILABLE"
            }

            expect("a rejected login, even nested in another jcifs failure, becomes non-retryable NasAuthenticationException") {
                val nested = SmbException("session setup failed", authFailure())

                val direct = shouldThrow<NasAuthenticationException> { sambaOperation("upload") { throw authFailure() } }
                val wrapped = shouldThrow<NasAuthenticationException> { sambaOperation("upload") { throw nested } }

                wrapped.cause shouldBeSameInstanceAs nested
                TransientProcessingFailure.reason(direct) shouldBe null
                TransientProcessingFailure.reason(wrapped) shouldBe null
            }

            expect("an authentication failure stays non-retryable when the chain also has a timeout") {
                val denied = mockk<SmbAuthException>(relaxed = true) { every { cause } returns SocketTimeoutException() }

                val thrown = shouldThrow<NasAuthenticationException> { sambaOperation("upload") { throw denied } }

                TransientProcessingFailure.reason(thrown) shouldBe null
            }

            expect("suppressed close failures stay on the jcifs failure kept as the cause") {
                val failure = SmbException("write failed")
                val closeFailure = SmbException("close failed")
                failure.addSuppressed(closeFailure)

                val thrown = shouldThrow<NasAccessException> { sambaOperation("upload") { throw failure } }

                thrown.cause!!.suppressed.toList() shouldContainExactly listOf(closeFailure)
            }

            expect("interruption stays visible through the translated chain") {
                val failure = SmbException("interrupted", InterruptedException())

                val thrown = shouldThrow<NasAccessException> { sambaOperation("upload") { throw failure } }

                ProcessingCancellation.isInterrupted(thrown) shouldBe true
            }

            expect("iteration failures (RuntimeCIFSException) are translated too") {
                val failure = RuntimeCIFSException("listing failed")

                shouldThrow<NasAccessException> { sambaOperation("directory listing") { throw failure } }.cause shouldBeSameInstanceAs
                    failure
            }

            expect("non-jcifs failures propagate unchanged") {
                val validation = IllegalArgumentException("Unsafe NAS path")
                val interrupted = InterruptedException()
                val callback = IllegalStateException("claim lost")

                listOf(validation, interrupted, callback).forEach { failure ->
                    shouldThrow<Exception> { sambaOperation("upload") { throw failure } } shouldBeSameInstanceAs failure
                }
            }

            expect("results and non-local returns pass through") {
                fun firstPositive(values: List<Int>): Int {
                    sambaOperation("lookup") { values.forEach { if (it > 0) return it } }
                    return -1
                }

                sambaOperation("lookup") { 42 } shouldBe 42
                firstPositive(listOf(-1, 3, 5)) shouldBe 3
            }
        }

        context("streams") {
            expect("input stream read and close failures are storage exceptions") {
                val readFailure = SmbException("read failed")
                val delegate = mockk<InputStream>()
                every { delegate.read(any(), any(), any()) } throws readFailure
                every { delegate.close() } throws SmbException("close failed")
                val stream = SambaInputStream(delegate, "read")

                shouldThrow<NasAccessException> { stream.read(ByteArray(4), 0, 4) }.cause shouldBeSameInstanceAs readFailure
                shouldThrow<NasAccessException> { stream.close() }
            }

            expect("output stream write failures are storage exceptions; other failures are unchanged") {
                val delegate = mockk<OutputStream>()
                val failure = IllegalStateException("not jcifs")
                every { delegate.write(any<ByteArray>(), any(), any()) } throws SmbException("write failed")
                every { delegate.flush() } throws failure
                val stream = SambaOutputStream(delegate, "write")

                shouldThrow<NasAccessException> { stream.write(ByteArray(4), 0, 4) }
                shouldThrow<IllegalStateException> { stream.flush() } shouldBeSameInstanceAs failure
            }
        }
    })
