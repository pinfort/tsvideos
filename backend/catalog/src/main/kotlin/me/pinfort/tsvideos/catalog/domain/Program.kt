package me.pinfort.tsvideos.catalog.domain

import java.time.LocalDateTime

data class Program(
    val id: Long,
    val name: String,
    val executedFileId: Long,
    val status: Status,
    val drops: Int,
    val size: Long,
    val recordedAt: LocalDateTime,
    val channel: String,
    val title: String,
    val channelName: String,
    val duration: Double,
) {
    enum class Status {
        REGISTERED,
        COMPLETED,
        ERROR,
    }
}
