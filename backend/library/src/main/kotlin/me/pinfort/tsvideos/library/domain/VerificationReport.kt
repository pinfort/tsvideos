package me.pinfort.tsvideos.library.domain

import java.time.Instant

enum class VerificationStatus {
    VERIFIED,
    MISSING,
    SIZE_MISMATCH,
    INACCESSIBLE,
    UNCHECKED,
}

data class FileFinding(
    val programId: Long,
    val fileId: Long?,
    val file: String?,
    val status: VerificationStatus,
    val checkedAt: Instant,
    val expectedBytes: Long?,
    val actualBytes: Long?,
    val reason: String?,
)

data class VerificationReport(
    val findings: List<FileFinding>,
) {
    val summary: Map<VerificationStatus, Int> =
        VerificationStatus.entries.associateWith { status ->
            findings.count { it.status == status }
        }
    val hasIssues: Boolean = findings.any { it.status != VerificationStatus.VERIFIED }
}
