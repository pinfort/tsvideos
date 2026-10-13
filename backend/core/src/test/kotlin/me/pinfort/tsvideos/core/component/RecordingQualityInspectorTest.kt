package me.pinfort.tsvideos.core.component

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.config.RecordingQualityConfigurationProperties
import me.pinfort.tsvideos.core.domain.QualityAction
import me.pinfort.tsvideos.core.domain.RecordingStream
import me.pinfort.tsvideos.core.external.tool.RecordingStreamProbeClient
import java.io.File

class RecordingQualityInspectorTest :
    ExpectSpec({
        expect("disabled and numeric-only policies never probe streams") {
            val probe = mockk<RecordingStreamProbeClient>()
            val disabled = RecordingQualityInspector(RecordingQualityConfigurationProperties(), probe)
            disabled.enabled() shouldBe false
            disabled.inspect(File("original"), -1, 0.0).findings shouldBe emptyList()
            val numeric = RecordingQualityInspector(RecordingQualityConfigurationProperties(maxDroppedFrames = 0), probe)
            numeric.enabled() shouldBe true
            numeric.inspect(File("original"), 1, 1.0).action shouldBe QualityAction.WARN
            verify(exactly = 0) { probe.probe(any()) }
        }
        expect("configured stream rules use explicit unavailable action") {
            val probe = mockk<RecordingStreamProbeClient> { every { probe(any()) } returns null }
            val inspector =
                RecordingQualityInspector(RecordingQualityConfigurationProperties(requiredStreams = setOf(RecordingStream.VIDEO)), probe)
            inspector.inspect(File("original"), 0, 1.0).action shouldBe QualityAction.WARN
            verify(exactly = 1) { probe.probe(any()) }
        }
    })
