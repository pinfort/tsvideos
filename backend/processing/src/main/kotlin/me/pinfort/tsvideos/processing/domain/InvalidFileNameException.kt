package me.pinfort.tsvideos.processing.domain

class InvalidFileNameException(
    message: String?,
    cause: Throwable? = null,
) : TsVideosException(message, cause)
