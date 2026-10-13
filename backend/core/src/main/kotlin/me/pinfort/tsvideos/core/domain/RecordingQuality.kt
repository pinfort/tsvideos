package me.pinfort.tsvideos.core.domain

enum class QualityAction { CONTINUE, WARN, QUARANTINE }

enum class RecordingStream { AUDIO, VIDEO, SUBTITLE }

enum class QualityReason { WITHIN_LIMIT, THRESHOLD_EXCEEDED, REQUIRED_STREAM_MISSING, MEASUREMENT_UNAVAILABLE }

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
        require(maxDroppedFrames == null || maxDroppedFrames.threshold >= 0)
        require(minDurationSeconds == null || (minDurationSeconds.threshold.isFinite() && minDurationSeconds.threshold > 0))
        require(requiredStreams == null || requiredStreams.threshold.isNotEmpty())
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
        policy.maxDroppedFrames?.let {
            val actual = measurements.drops?.takeIf { value -> value >= 0 }
            finding(
                "MAX_DROPPED_FRAMES",
                it.threshold.toString(),
                actual?.toString(),
                actual?.let { value ->
                    value <= it.threshold
                },
                QualityReason.THRESHOLD_EXCEEDED,
                it.action,
            )
        }
        policy.minDurationSeconds?.let {
            val actual = measurements.durationSeconds?.takeIf { value -> value.isFinite() && value > 0 }
            finding(
                "MIN_DURATION_SECONDS",
                it.threshold.toString(),
                actual?.toString(),
                actual?.let { value ->
                    value >= it.threshold
                },
                QualityReason.THRESHOLD_EXCEEDED,
                it.action,
            )
        }
        policy.requiredStreams?.let {
            finding(
                "REQUIRED_STREAMS",
                it.threshold
                    .map { value ->
                        value.name
                    }.sorted()
                    .joinToString(","),
                measurements.streams
                    ?.map { value ->
                        value.name
                    }?.sorted()
                    ?.joinToString(","),
                measurements.streams?.containsAll(it.threshold),
                QualityReason.REQUIRED_STREAM_MISSING,
                it.action,
            )
        }
        return RecordingQualityDecision(findings.maxByOrNull { it.action.ordinal }?.action ?: QualityAction.CONTINUE, findings)
    }
}
