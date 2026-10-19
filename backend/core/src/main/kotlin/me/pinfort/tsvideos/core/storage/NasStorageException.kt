package me.pinfort.tsvideos.core.storage

import java.io.IOException

/**
 * Storage failures exposed to callers instead of implementation (Samba/jcifs) exceptions.
 * Messages never contain server URLs or credentials; the implementation exception is kept as the cause, so
 * cause-chain checks (network timeouts, interruption) and suppressed close failures stay observable.
 */
open class NasStorageException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * The NAS could not complete the operation: the share or file is unreachable or missing, or an I/O request failed.
 * The implementation cannot always tell these apart.
 */
class NasAccessException(
    message: String,
    cause: Throwable? = null,
) : NasStorageException(message, cause)

/** The NAS rejected the configured credentials. Never transient, so it is not retried. */
class NasAuthenticationException(
    message: String,
    cause: Throwable? = null,
) : NasStorageException(message, cause)
