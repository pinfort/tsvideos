package me.pinfort.tsvideos.processing.api

import me.pinfort.tsvideos.processing.api.config.RecordingQualityConfigurationProperties
import me.pinfort.tsvideos.processing.api.tool.RecordingStreamProbeClient
import me.pinfort.tsvideos.processing.domain.RecordingQualityDecision
import me.pinfort.tsvideos.processing.domain.RecordingQualityEvaluator
import me.pinfort.tsvideos.processing.domain.RecordingQualityMeasurements
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
