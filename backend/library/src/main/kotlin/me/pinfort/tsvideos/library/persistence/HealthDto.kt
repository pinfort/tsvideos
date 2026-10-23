package me.pinfort.tsvideos.library.persistence

import me.pinfort.tsvideos.library.domain.FileFinding
import me.pinfort.tsvideos.library.domain.HealthRun
import me.pinfort.tsvideos.library.domain.VerificationStatus
import java.time.LocalDateTime
import java.time.ZoneOffset

internal data class HealthRunDto(
    val id: Long,
    val state: HealthRun.State,
    val startedAt: LocalDateTime,
    val completedAt: LocalDateTime?,
    val failureCode: String?,
) {
    fun toDomain() = HealthRun(id, state, startedAt.toInstant(ZoneOffset.UTC), completedAt?.toInstant(ZoneOffset.UTC), failureCode)
}

internal data class HealthFindingDto(
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
