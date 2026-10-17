package me.pinfort.tsvideos.core.config

import me.pinfort.tsvideos.core.domain.QualityAction
import me.pinfort.tsvideos.core.domain.QualityRule
import me.pinfort.tsvideos.core.domain.RecordingQualityPolicy
import me.pinfort.tsvideos.core.domain.RecordingStream
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "processor-quality")
data class RecordingQualityConfigurationProperties(
    val maxDroppedFrames: Int? = null,
    val dropsAction: QualityAction = QualityAction.WARN,
    val minDurationSeconds: Double? = null,
    val durationAction: QualityAction = QualityAction.WARN,
    val requiredStreams: Set<RecordingStream> = emptySet(),
    val streamsAction: QualityAction = QualityAction.WARN,
    val unavailableAction: QualityAction = QualityAction.WARN,
) {
    // Built once only to validate: a misconfigured policy fails application startup instead of the first recording.
    init {
        policy()
    }

    fun policy() =
        RecordingQualityPolicy(
            maxDroppedFrames?.let { QualityRule(it, dropsAction) },
            minDurationSeconds?.let { QualityRule(it, durationAction) },
            requiredStreams.takeIf { it.isNotEmpty() }?.let { QualityRule(it, streamsAction) },
            unavailableAction,
        )
}
