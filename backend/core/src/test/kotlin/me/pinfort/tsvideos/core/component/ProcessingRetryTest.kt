package me.pinfort.tsvideos.core.component

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.config.ProcessingRetryConfigurationProperties
import me.pinfort.tsvideos.core.storage.NasAuthenticationException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Import
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.AccessDeniedException

/** The processor application registers the same properties through its scan of the core config package. */
@EnableConfigurationProperties(ProcessingRetryConfigurationProperties::class)
@Import(ProcessingRetry::class, ProcessingRetryRuntime::class, ProcessingCancellation::class)
private class RetryContext

class ProcessingRetryTest :
    ExpectSpec({
        fun retry(
            attempts: Int,
            delays: MutableList<Long>,
            pause: (Long) -> Unit = {},
        ): ProcessingRetry {
            val runtime = mockk<ProcessingRetryRuntime>()
            every { runtime.random() } returns 0.0
            every { runtime.pause(any()) } answers {
                val delay = firstArg<Long>()
                delays.add(delay)
                pause(delay)
            }
            return ProcessingRetry(ProcessingRetryConfigurationProperties(attempts, 100, 150), runtime)
        }
        expect("bounded backoff with jitter retries transient eligible operations and returns success") {
            val delays = mutableListOf<Long>()
            var calls = 0
            retry(4, delays).execute(RetryOperation.UPLOAD, { true }) {
                if (++calls < 4) throw SocketTimeoutException("secret")
                "done"
            } shouldBe "done"
            delays shouldBe listOf(50L, 75L, 75L)
            calls shouldBe 4
        }
        expect("exhaustion rethrows final exception and limits total attempts") {
            val delays = mutableListOf<Long>()
            var calls = 0
            val failure = SocketTimeoutException()
            shouldThrow<SocketTimeoutException> {
                retry(3, delays).execute(RetryOperation.UPLOAD, { true }) {
                    calls++
                    throw failure
                }
            } shouldBe failure
            calls shouldBe 3
            delays.size shouldBe 2
        }
        expect("permanent or ambiguous state never retries") {
            for (failure in listOf(IOException(), AccessDeniedException("input"), IllegalArgumentException(), IllegalStateException())) {
                var calls = 0
                val delays = mutableListOf<Long>()
                shouldThrow<Exception> {
                    retry(3, delays).execute(RetryOperation.UPLOAD, { true }) {
                        calls++
                        throw failure
                    }
                }
                calls shouldBe 1
                delays.size shouldBe 0
            }
            val delays = mutableListOf<Long>()
            shouldThrow<SocketTimeoutException> {
                retry(
                    3,
                    delays,
                ).execute(RetryOperation.UPLOAD, { false }) { throw SocketTimeoutException() }
            }
            delays.size shouldBe 0
        }
        expect("backoff cancellation propagates immediately with interrupt flag") {
            val delays = mutableListOf<Long>()
            var calls = 0
            try {
                shouldThrow<InterruptedException> {
                    retry(3, delays) { throw InterruptedException() }.execute(RetryOperation.UPLOAD, { true }) {
                        calls++
                        throw SocketTimeoutException()
                    }
                }
                calls shouldBe 1
                Thread.currentThread().isInterrupted shouldBe true
            } finally {
                Thread.interrupted()
            }
        }
        expect("failed eligibility inspection preserves original failure and prevents retry") {
            val original = SocketTimeoutException()
            val proofFailure = IOException()
            val delays = mutableListOf<Long>()
            shouldThrow<SocketTimeoutException> {
                retry(3, delays).execute(RetryOperation.UPLOAD, { throw proofFailure }) { throw original }
            } shouldBe original
            original.suppressed.single() shouldBe proofFailure
            delays.size shouldBe 0
        }
        expect("permanent outer types override nested transient errors") {
            val failures =
                listOf(
                    java.io.FileNotFoundException().apply { initCause(SocketTimeoutException()) },
                    java.nio.file
                        .NoSuchFileException("missing")
                        .apply { initCause(SocketTimeoutException()) },
                    java.sql.SQLNonTransientException("invalid", SocketTimeoutException()),
                    NasAuthenticationException("NAS upload failed: credentials rejected", SocketTimeoutException()),
                )
            failures.forEach { TransientProcessingFailure.reason(it) shouldBe null }
        }
        expect("eligibility is rechecked after backoff and changed ownership prevents next attempt") {
            var proofs = 0
            var calls = 0
            val failure = SocketTimeoutException()
            shouldThrow<SocketTimeoutException> {
                retry(3, mutableListOf()).execute(RetryOperation.UPLOAD, { ++proofs == 1 }) {
                    calls++
                    throw failure
                }
            } shouldBe failure
            calls shouldBe 1
            proofs shouldBe 2
        }
        expect("classification is bounded and only a retry-refused marker blocks retry") {
            TransientProcessingFailure.reason(IOException("wrapped", SocketTimeoutException())) shouldBe "NETWORK_UNAVAILABLE"
            val timeout = SocketTimeoutException().apply { addSuppressed(IOException("cleanup failed")) }
            TransientProcessingFailure.reason(timeout) shouldBe "NETWORK_UNAVAILABLE"
            timeout.addSuppressed(RetryRefusedException("cleanup failed; retry refused"))
            TransientProcessingFailure.reason(timeout) shouldBe null
            shouldThrow<IllegalArgumentException> { ProcessingRetryConfigurationProperties(0) }
            shouldThrow<IllegalArgumentException> {
                ProcessingRetryConfigurationProperties(
                    initialDelayMillis = 1000,
                    maxDelayMillis = 100,
                )
            }
        }
        expect("documented processor-retry properties reach the Spring retry bean") {
            ApplicationContextRunner()
                .withUserConfiguration(RetryContext::class.java)
                .withPropertyValues("processor-retry.max-attempts=1")
                .run { context ->
                    context.getBean(ProcessingRetryConfigurationProperties::class.java).maxAttempts shouldBe 1
                    var calls = 0
                    shouldThrow<SocketTimeoutException> {
                        context.getBean(ProcessingRetry::class.java).execute(RetryOperation.UPLOAD, { true }) {
                            calls++
                            throw SocketTimeoutException()
                        }
                    }
                    calls shouldBe 1
                }
        }
    })
