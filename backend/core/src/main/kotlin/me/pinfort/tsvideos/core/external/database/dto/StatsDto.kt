package me.pinfort.tsvideos.core.external.database.dto

import java.time.LocalDateTime

data class StatsProgramDto(
    val id: Long,
    val executedFileId: Long,
    val status: String,
    val drops: Int?,
    val channel: String?,
    val recordedAt: LocalDateTime?,
)

data class StatsFileDto(
    val id: Long,
    val executedFileId: Long,
    val size: Long,
    val mime: String?,
    val encoding: String?,
    val status: String,
)
