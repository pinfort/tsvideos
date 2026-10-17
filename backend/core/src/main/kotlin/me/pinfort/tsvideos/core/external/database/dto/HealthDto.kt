package me.pinfort.tsvideos.core.external.database.dto

import me.pinfort.tsvideos.core.domain.FileFinding
import me.pinfort.tsvideos.core.domain.HealthRun
import me.pinfort.tsvideos.core.domain.VerificationStatus
import java.time.LocalDateTime
import java.time.ZoneOffset

data class HealthRunDto(
    val id: Long,
    val state: HealthRun.State,
    val startedAt: LocalDateTime,
    val completedAt: LocalDateTime?,
    val failureCode: String?,
) {
    fun toDomain() = HealthRun(id, state, startedAt.toInstant(ZoneOffset.UTC), completedAt?.toInstant(ZoneOffset.UTC), failureCode)
}

data class HealthFindingDto(
    val programId: Long,
    val fileId: Long?,
    val file: String?,
    val status: VerificationStatus,
    val checkedAt: LocalDateTime,
    val expectedBytes: Long?,
    val actualBytes: Long?,
    val reason: String?,
) {
    fun toDomain() = FileFinding(programId, fileId, file, status, checkedAt.toInstant(ZoneOffset.UTC), expectedBytes, actualBytes, reason)
}
