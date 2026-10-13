package me.pinfort.tsvideos.core.domain

import java.time.Instant

data class HealthProgram(
    val id: Long,
    val name: String,
    val status: String,
    val drops: Int?,
)

data class HealthRun(
    val id: Long,
    val state: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val failureCode: String?,
)

data class HealthFinding(
    val finding: FileFinding,
    val programName: String?,
    val programRemoved: Boolean,
)

data class LibraryHealth(
    val failedPrograms: List<HealthProgram>,
    val droppedPrograms: List<HealthProgram>,
    val latestRun: HealthRun?,
    val lastCompletedRun: HealthRun?,
    val findings: List<HealthFinding>,
    val summary: Map<VerificationStatus, Int>?,
)
