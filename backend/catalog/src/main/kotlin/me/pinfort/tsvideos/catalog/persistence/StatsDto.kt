package me.pinfort.tsvideos.catalog.persistence

import java.time.LocalDateTime

internal data class StatsProgramDto(
    val id: Long,
    val executedFileId: Long,
    val status: String,
    val drops: Int?,
    val channel: String?,
    val recordedAt: LocalDateTime?,
)

internal data class StatsFileDto(
    val id: Long,
    val executedFileId: Long,
    val size: Long,
    val mime: String?,
    val encoding: String?,
    val status: String,
)
