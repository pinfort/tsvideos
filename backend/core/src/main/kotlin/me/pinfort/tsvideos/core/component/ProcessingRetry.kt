package me.pinfort.tsvideos.core.component

import jcifs.smb.SmbAuthException
import me.pinfort.tsvideos.core.config.ProcessingRetryConfigurationProperties
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
                    it is SmbAuthException ||
                    it is SQLNonTransientException ||
                    it is SecurityException ||
                    it is IllegalArgumentException ||
                    it.suppressed.isNotEmpty()
            }
        ) {
            return null
        }
        return when {
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
        operation: String,
        eligible: () -> Boolean,
        action: () -> T,
    ): T {
        require(operation in setOf("UPLOAD", "REGISTER_UPLOAD", "VERIFY_UPLOAD"))
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
                        try {
                            eligible()
                        } catch (inspectionFailure: Exception) {
                            failure.addSuppressed(inspectionFailure)
                            if (ProcessingCancellation.isInterrupted(inspectionFailure)) Thread.currentThread().interrupt()
                            false
                        }
                if (!allowed) {
                    logger.warn(
                        "{} stopped at attempt {}/{} ({})",
                        operation,
                        attempt,
                        configuration.maxAttempts,
                        reason ?: "NOT_RETRYABLE",
                    )
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
                val stillEligible =
                    try {
                        eligible()
                    } catch (inspectionFailure: Exception) {
                        failure.addSuppressed(inspectionFailure)
                        if (ProcessingCancellation.isInterrupted(inspectionFailure)) Thread.currentThread().interrupt()
                        false
                    }
                if (!stillEligible) throw failure
                attempt++
                cap = (cap * 2).coerceAtMost(configuration.maxDelayMillis)
            }
        }
    }
}
