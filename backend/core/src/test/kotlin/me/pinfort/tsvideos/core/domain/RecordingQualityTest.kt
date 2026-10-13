package me.pinfort.tsvideos.core.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

class RecordingQualityTest :
    ExpectSpec({
        expect("unconfigured rules preserve processing even with unknown measurements") {
            RecordingQualityEvaluator.evaluate(RecordingQualityPolicy(), RecordingQualityMeasurements(-1, 0.0, null)) shouldBe
                RecordingQualityDecision(QualityAction.CONTINUE, emptyList())
        }
        expect("threshold boundaries are inclusive and the strongest configured action wins") {
            val policy = RecordingQualityPolicy(QualityRule(2, QualityAction.WARN), QualityRule(60.0, QualityAction.QUARANTINE))
            RecordingQualityEvaluator.evaluate(policy, RecordingQualityMeasurements(2, 60.0, null)).action shouldBe QualityAction.CONTINUE
            val decision = RecordingQualityEvaluator.evaluate(policy, RecordingQualityMeasurements(3, 59.0, null))
            decision.action shouldBe QualityAction.QUARANTINE
            decision.findings.map { it.reason } shouldBe listOf(QualityReason.THRESHOLD_EXCEEDED, QualityReason.THRESHOLD_EXCEEDED)
        }
        expect("unavailable measurements never appear as passing or zero") {
            val policy =
                RecordingQualityPolicy(
                    QualityRule(2),
                    QualityRule(60.0),
                    QualityRule(setOf(RecordingStream.VIDEO)),
                    QualityAction.QUARANTINE,
                )
            for (duration in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                val decision = RecordingQualityEvaluator.evaluate(policy, RecordingQualityMeasurements(-1, duration, null))
                decision.action shouldBe QualityAction.QUARANTINE
                decision.findings.all { it.observed == null && it.reason == QualityReason.MEASUREMENT_UNAVAILABLE } shouldBe true
            }
        }
        expect("empty known stream set is missing while null is unavailable") {
            val policy = RecordingQualityPolicy(requiredStreams = QualityRule(setOf(RecordingStream.VIDEO), QualityAction.QUARANTINE))
            RecordingQualityEvaluator
                .evaluate(policy, RecordingQualityMeasurements(null, null, emptySet()))
                .findings
                .single()
                .reason shouldBe
                QualityReason.REQUIRED_STREAM_MISSING
            RecordingQualityEvaluator.evaluate(policy, RecordingQualityMeasurements(null, null, null)).action shouldBe QualityAction.WARN
            RecordingQualityEvaluator
                .evaluate(
                    policy,
                    RecordingQualityMeasurements(null, null, setOf(RecordingStream.VIDEO, RecordingStream.AUDIO)),
                ).action shouldBe
                QualityAction.CONTINUE
        }
        expect("continue records a violated rule without stopping processing") {
            val decision =
                RecordingQualityEvaluator.evaluate(
                    RecordingQualityPolicy(maxDroppedFrames = QualityRule(0, QualityAction.CONTINUE)),
                    RecordingQualityMeasurements(10, null, null),
                )
            decision.action shouldBe QualityAction.CONTINUE
            decision.findings.single().reason shouldBe QualityReason.THRESHOLD_EXCEEDED
        }
        expect("invalid thresholds are rejected at configuration boundary") {
            shouldThrow<IllegalArgumentException> { RecordingQualityPolicy(maxDroppedFrames = QualityRule(-1)) }
            shouldThrow<IllegalArgumentException> { RecordingQualityPolicy(minDurationSeconds = QualityRule(Double.NaN)) }
            shouldThrow<IllegalArgumentException> { RecordingQualityPolicy(requiredStreams = QualityRule(emptySet())) }
        }
    })
