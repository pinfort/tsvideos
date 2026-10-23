package me.pinfort.tsvideos.processing.domain

/** Declared in increasing severity: the evaluator keeps the highest ordinal, so never reorder these. */
enum class QualityAction { CONTINUE, WARN, QUARANTINE }

enum class RecordingStream { AUDIO, VIDEO, SUBTITLE }

/** Persisted in retained report JSON: add values, never rename them. */
enum class QualityReason { WITHIN_LIMIT, THRESHOLD_EXCEEDED, BELOW_MINIMUM, REQUIRED_STREAM_MISSING, MEASUREMENT_UNAVAILABLE }

data class QualityRule<T>(
    val threshold: T,
    val action: QualityAction = QualityAction.WARN,
)

data class RecordingQualityPolicy(
    val maxDroppedFrames: QualityRule<Int>? = null,
    val minDurationSeconds: QualityRule<Double>? = null,
    val requiredStreams: QualityRule<Set<RecordingStream>>? = null,
    val unavailableAction: QualityAction = QualityAction.WARN,
) {
    init {
        require(maxDroppedFrames == null || maxDroppedFrames.threshold >= 0) {
            "processor-quality.max-dropped-frames must be zero or more"
        }
        require(minDurationSeconds == null || (minDurationSeconds.threshold.isFinite() && minDurationSeconds.threshold > 0)) {
            "processor-quality.min-duration-seconds must be a finite positive number"
        }
        require(requiredStreams == null || requiredStreams.threshold.isNotEmpty()) {
            "processor-quality.required-streams must not be empty"
        }
    }
}

data class RecordingQualityMeasurements(
    val drops: Int?,
    val durationSeconds: Double?,
    val streams: Set<RecordingStream>?,
)

data class RecordingQualityFinding(
    val rule: String,
    val configured: String,
    val observed: String?,
    val reason: QualityReason,
    val action: QualityAction,
)

data class RecordingQualityDecision(
    val action: QualityAction,
    val findings: List<RecordingQualityFinding>,
)

/** Pure policy evaluation: unavailable measurements are distinct from successful checks. */
object RecordingQualityEvaluator {
    fun evaluate(
        policy: RecordingQualityPolicy,
        measurements: RecordingQualityMeasurements,
    ): RecordingQualityDecision {
        val findings = mutableListOf<RecordingQualityFinding>()

        fun finding(
            rule: String,
            configured: String,
            observed: String?,
            passed: Boolean?,
            failure: QualityReason,
            action: QualityAction,
        ) {
            findings +=
                RecordingQualityFinding(
                    rule,
                    configured,
                    observed,
                    when (passed) {
                        null -> QualityReason.MEASUREMENT_UNAVAILABLE
                        true -> QualityReason.WITHIN_LIMIT
                        false -> failure
                    },
                    when (passed) {
                        null -> policy.unavailableAction
                        true -> QualityAction.CONTINUE
                        false -> action
                    },
                )
        }
        policy.maxDroppedFrames?.let { rule ->
            val actual = measurements.drops?.takeIf { it >= 0 }
            finding(
                "MAX_DROPPED_FRAMES",
                rule.threshold.toString(),
                actual?.toString(),
                actual?.let { it <= rule.threshold },
                QualityReason.THRESHOLD_EXCEEDED,
                rule.action,
            )
        }
        policy.minDurationSeconds?.let { rule ->
            val actual = measurements.durationSeconds?.takeIf { it.isFinite() && it > 0 }
            finding(
                "MIN_DURATION_SECONDS",
                rule.threshold.toString(),
                actual?.toString(),
                actual?.let { it >= rule.threshold },
                QualityReason.BELOW_MINIMUM,
                rule.action,
            )
        }
        policy.requiredStreams?.let { rule ->
            finding(
                "REQUIRED_STREAMS",
                rule.threshold.names(),
                measurements.streams?.names(),
                measurements.streams?.containsAll(rule.threshold),
                QualityReason.REQUIRED_STREAM_MISSING,
                rule.action,
            )
        }
        return RecordingQualityDecision(findings.maxByOrNull { it.action.ordinal }?.action ?: QualityAction.CONTINUE, findings)
    }
}

private fun Set<RecordingStream>.names() = map { it.name }.sorted().joinToString(",")
