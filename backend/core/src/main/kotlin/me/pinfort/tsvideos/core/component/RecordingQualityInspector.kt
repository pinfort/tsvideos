package me.pinfort.tsvideos.core.component

import me.pinfort.tsvideos.core.config.RecordingQualityConfigurationProperties
import me.pinfort.tsvideos.core.domain.RecordingQualityDecision
import me.pinfort.tsvideos.core.domain.RecordingQualityEvaluator
import me.pinfort.tsvideos.core.domain.RecordingQualityMeasurements
import me.pinfort.tsvideos.core.external.tool.RecordingStreamProbeClient
import org.springframework.stereotype.Component
import java.io.File

@Component
class RecordingQualityInspector(
    private val configuration: RecordingQualityConfigurationProperties,
    private val streams: RecordingStreamProbeClient,
) {
    fun enabled(): Boolean =
        configuration.policy().let {
            it.maxDroppedFrames != null || it.minDurationSeconds != null || it.requiredStreams != null
        }

    fun inspect(
        file: File,
        drops: Int,
        durationSeconds: Double,
    ): RecordingQualityDecision {
        val policy = configuration.policy()
        val measured = if (policy.requiredStreams != null) streams.probe(file) else null
        return RecordingQualityEvaluator.evaluate(policy, RecordingQualityMeasurements(drops, durationSeconds, measured))
    }
}
