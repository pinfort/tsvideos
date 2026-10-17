package me.pinfort.tsvideos.core.domain

enum class DiagnosticStatus {
    PASS,
    FAIL,
    UNCHECKED,
}

data class DiagnosticCheck(
    val id: String,
    val status: DiagnosticStatus,
    val reason: String,
)

data class DiagnosticReport(
    val checks: List<DiagnosticCheck>,
) {
    val hasFailures: Boolean = checks.any { it.status == DiagnosticStatus.FAIL }
}
