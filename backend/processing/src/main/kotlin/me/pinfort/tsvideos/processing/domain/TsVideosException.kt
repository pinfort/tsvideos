package me.pinfort.tsvideos.processing.domain

open class TsVideosException(
    message: String?,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
