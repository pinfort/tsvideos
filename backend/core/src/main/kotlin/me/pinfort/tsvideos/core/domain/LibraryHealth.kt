package me.pinfort.tsvideos.core.domain

import java.time.Instant

data class HealthProgram(
    val id: Long,
    val name: String,
    val status: Program.Status,
    val drops: Int?,
)

data class HealthRun(
    val id: Long,
    val state: State,
    val startedAt: Instant,
    val completedAt: Instant?,
    val failureCode: String?,
) {
    enum class State {
        RUNNING,
        COMPLETED,
        FAILED,
    }
}

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
