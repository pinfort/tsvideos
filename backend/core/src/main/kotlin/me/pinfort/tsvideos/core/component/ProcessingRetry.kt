package me.pinfort.tsvideos.core.component

import me.pinfort.tsvideos.core.config.ProcessingRetryConfigurationProperties
import me.pinfort.tsvideos.core.storage.NasAuthenticationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.sql.SQLNonTransientException
import java.sql.SQLTransientConnectionException
import kotlin.random.Random

@Component
class ProcessingRetryRuntime {
    fun pause(milliseconds: Long) = Thread.sleep(milliseconds)

    fun random(): Double = Random.nextDouble()
}

/** Retryable operations; each has durable manifest reconciliation in VerifiedUploadCommand. */
enum class RetryOperation { UPLOAD, REGISTER_UPLOAD, VERIFY_UPLOAD }

/**
 * Attached as a suppressed exception when the durable state is unproven (cleanup or claim release failed), so a
 * transient-looking primary failure is not retried. Other suppressed exceptions, such as close failures that
 * try-with-resources or jcifs attach, do not veto a retry.
 */
class RetryRefusedException(
    message: String,
) : IllegalStateException(message)

/** Typed conservative classification: a generic IO error is not evidence of a transient network error. */
object TransientProcessingFailure {
    fun reason(failure: Throwable): String? {
        val causes = generateSequence(failure) { it.cause }.take(16).toList()
        if (Thread.currentThread().isInterrupted ||
            causes.any {
                it is InterruptedException ||
                    it is AccessDeniedException ||
                    it is NoSuchFileException ||
                    it is FileNotFoundException ||
                    // NAS operations translate a rejected login (anywhere in the jcifs chain) into this type.
                    it is NasAuthenticationException ||
                    it is SQLNonTransientException ||
                    it is SecurityException ||
                    it is IllegalArgumentException ||
                    it.suppressed.any { suppressed -> suppressed is RetryRefusedException }
            }
        ) {
            return null
        }
        return when {
            // A read timeout may arrive after the server applied the operation. That is safe only because callers
            // re-check that the durable state is unambiguous (never CREATING/FINALIZING) before retrying.
            causes.any { it is SocketTimeoutException || it is ConnectException || it is NoRouteToHostException } -> "NETWORK_UNAVAILABLE"
            causes.any { it is SQLTransientConnectionException } -> "DATABASE_CONNECTION_UNAVAILABLE"
            else -> null
        }
    }
}

/** Only callers with durable reconciliation may supply eligibility; no generic pipeline retry. */
@Component
class ProcessingRetry(
    private val configuration: ProcessingRetryConfigurationProperties = ProcessingRetryConfigurationProperties(),
    private val runtime: ProcessingRetryRuntime = ProcessingRetryRuntime(),
    private val cancellation: ProcessingCancellation = ProcessingCancellation(),
) {
    private val logger = LoggerFactory.getLogger(ProcessingRetry::class.java)

    fun <T> execute(
        operation: RetryOperation,
        eligible: () -> Boolean,
        action: () -> T,
    ): T {
        var attempt = 1
        var cap = configuration.initialDelayMillis
        while (true) {
            cancellation.check()
            try {
                return action()
            } catch (failure: Exception) {
                if (ProcessingCancellation.isInterrupted(failure)) {
                    Thread.currentThread().interrupt()
                    throw failure
                }
                val reason = TransientProcessingFailure.reason(failure)
                val allowed =
                    reason != null &&
                        attempt < configuration.maxAttempts &&
                        inspect(failure, eligible)
                if (!allowed) {
                    // A permanent failure is logged by the caller; only a stopped transient retry deserves a warning.
                    if (reason == null) {
                        logger.debug("{} stopped at attempt {}/{} (NOT_RETRYABLE)", operation, attempt, configuration.maxAttempts)
                    } else {
                        logger.warn("{} stopped at attempt {}/{} ({})", operation, attempt, configuration.maxAttempts, reason)
                    }
                    throw failure
                }
                cancellation.check()
                val jitter = runtime.random().coerceIn(0.0, 1.0)
                val delay = (cap * (0.5 + jitter * 0.5)).toLong().coerceAtLeast(1)
                logger.warn("{} retry attempt {}/{} after {} ms ({})", operation, attempt + 1, configuration.maxAttempts, delay, reason)
                try {
                    runtime.pause(delay)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
                cancellation.check()
                // Backoff is not a lease: another owner may have advanced the durable state.
                if (!inspect(failure, eligible)) throw failure
                attempt++
                cap = (cap * 2).coerceAtMost(configuration.maxDelayMillis)
            }
        }
    }

    /** An inspection failure is kept on the original failure and counts as "not eligible". */
    private fun inspect(
        failure: Exception,
        eligible: () -> Boolean,
    ): Boolean =
        try {
            eligible()
        } catch (inspectionFailure: Exception) {
            failure.addSuppressed(inspectionFailure)
            if (ProcessingCancellation.isInterrupted(inspectionFailure)) Thread.currentThread().interrupt()
            false
        }
}
