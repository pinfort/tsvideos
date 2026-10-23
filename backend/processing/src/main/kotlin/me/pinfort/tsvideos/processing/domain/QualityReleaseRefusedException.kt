package me.pinfort.tsvideos.processing.domain

/** A quarantine release refused for a known, operator-actionable reason. Messages carry no paths. */
class QualityReleaseRefusedException(
    val reason: Reason,
) : IllegalStateException(reason.description) {
    enum class Reason(
        val description: String,
    ) {
        NOT_QUARANTINED("job is not quarantined, or is registered or claimed by a callback"),
        ACTIVE_ATTEMPT("an invocation of this job is still active"),
        SOURCE_CHANGED("the quarantined original changed or is missing"),
        CHANGED_CONCURRENTLY("the job changed during release"),
    }
}
