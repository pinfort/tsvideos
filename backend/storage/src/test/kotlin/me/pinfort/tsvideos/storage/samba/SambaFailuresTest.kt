package me.pinfort.tsvideos.storage.samba

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import jcifs.RuntimeCIFSException
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import me.pinfort.tsvideos.storage.api.NasAccessException
import me.pinfort.tsvideos.storage.api.NasAuthenticationException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

// Retry classification of the translated types is asserted by the caller (processing's ProcessingStorageCallerTest);
// here the translation keeps the type and the causes that classification reads.
class SambaFailuresTest :
    ExpectSpec({
        fun authFailure(): SmbAuthException = mockk(relaxed = true) { every { cause } returns null }

        context("sambaOperation") {
            expect("a network failure becomes NasAccessException and keeps the timeout in its cause chain") {
                val failure = SmbException("smb://user:secret@nas/share timed out", SocketTimeoutException())

                val thrown = shouldThrow<NasAccessException> { sambaOperation("upload") { throw failure } }

                thrown.cause shouldBeSameInstanceAs failure
                thrown.message shouldNotContain "smb://"
                thrown.message shouldNotContain "secret"
                thrown.causeChain().any { it is SocketTimeoutException } shouldBe true
            }

            expect("a rejected login, even nested in another jcifs failure, becomes NasAuthenticationException") {
                val nested = SmbException("session setup failed", authFailure())

                shouldThrow<NasAuthenticationException> { sambaOperation("upload") { throw authFailure() } }
                val wrapped = shouldThrow<NasAuthenticationException> { sambaOperation("upload") { throw nested } }

                wrapped.cause shouldBeSameInstanceAs nested
            }

            expect("an authentication failure stays NasAuthenticationException when the chain also has a timeout") {
                val denied = mockk<SmbAuthException>(relaxed = true) { every { cause } returns SocketTimeoutException() }

                shouldThrow<NasAuthenticationException> { sambaOperation("upload") { throw denied } }
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

                thrown.causeChain().any { it is InterruptedException } shouldBe true
            }

            expect("iteration failures (RuntimeCIFSException) are translated too") {
                val failure = RuntimeCIFSException("listing failed")

                shouldThrow<NasAccessException> { sambaOperation("directory listing") { throw failure } }.cause shouldBeSameInstanceAs
                    failure
            }

            context("non-jcifs failures propagate unchanged") {
                withData(
                    mapOf<String, () -> Exception>(
                        "validation" to { IllegalArgumentException("Unsafe NAS path") },
                        "interruption" to { InterruptedException() },
                        "callback" to { IllegalStateException("claim lost") },
                    ),
                ) { newFailure ->
                    val failure = newFailure()
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
