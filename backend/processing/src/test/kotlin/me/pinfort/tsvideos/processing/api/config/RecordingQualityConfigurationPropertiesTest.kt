package me.pinfort.tsvideos.processing.api.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import me.pinfort.tsvideos.processing.domain.QualityAction
import me.pinfort.tsvideos.processing.domain.QualityRule
import me.pinfort.tsvideos.processing.domain.RecordingQualityPolicy
import me.pinfort.tsvideos.processing.domain.RecordingStream
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

class RecordingQualityConfigurationPropertiesTest :
    ExpectSpec({
        fun bindEnvironment(variables: Map<String, Any>): RecordingQualityConfigurationProperties {
            val environment = StandardEnvironment()
            environment.propertySources.addFirst(SystemEnvironmentPropertySource("test-systemEnvironment", variables))
            return Binder(ConfigurationPropertySources.get(environment))
                .bindOrCreate("processor-quality", RecordingQualityConfigurationProperties::class.java)
        }

        expect("documented environment variables bind thresholds, stream list and actions") {
            bindEnvironment(
                mapOf(
                    "PROCESSORQUALITY_MAXDROPPEDFRAMES" to "0",
                    "PROCESSORQUALITY_DROPSACTION" to "QUARANTINE",
                    "PROCESSORQUALITY_MINDURATIONSECONDS" to "60",
                    "PROCESSORQUALITY_REQUIREDSTREAMS" to "VIDEO,AUDIO",
                    "PROCESSORQUALITY_STREAMSACTION" to "quarantine",
                    "PROCESSORQUALITY_UNAVAILABLEACTION" to "WARN",
                ),
            ).policy() shouldBe
                RecordingQualityPolicy(
                    QualityRule(0, QualityAction.QUARANTINE),
                    QualityRule(60.0, QualityAction.WARN),
                    QualityRule(setOf(RecordingStream.VIDEO, RecordingStream.AUDIO), QualityAction.QUARANTINE),
                    QualityAction.WARN,
                )
        }
        expect("no configuration means no rules") {
            bindEnvironment(emptyMap()).policy() shouldBe RecordingQualityPolicy()
        }
        expect("yaml-style keys bind and an invalid threshold fails binding with its property name") {
            Binder(MapConfigurationPropertySource(mapOf("processor-quality.required-streams" to "SUBTITLE")))
                .bindOrCreate("processor-quality", RecordingQualityConfigurationProperties::class.java)
                .policy()
                .requiredStreams shouldBe QualityRule(setOf(RecordingStream.SUBTITLE))
            val failure =
                shouldThrow<BindException> {
                    Binder(MapConfigurationPropertySource(mapOf("processor-quality.max-dropped-frames" to "-1")))
                        .bindOrCreate("processor-quality", RecordingQualityConfigurationProperties::class.java)
                }
            generateSequence<Throwable>(failure) { it.cause }.last().message!! shouldContain "processor-quality.max-dropped-frames"
        }
    })
