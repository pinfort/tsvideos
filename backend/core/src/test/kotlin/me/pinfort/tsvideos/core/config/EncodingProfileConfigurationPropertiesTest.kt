package me.pinfort.tsvideos.core.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.component.EncodingProfileSelector
import me.pinfort.tsvideos.core.domain.EncodingProfileInput
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class EncodingProfileConfigurationPropertiesTest :
    ExpectSpec({
        val tools =
            ProcessorToolConfigurationProperties(
                "split",
                "encoder",
                "probe",
                ProcessorToolConfigurationProperties.Amatsukaze("localhost", 1, "default", "atx"),
            )

        fun bind(values: Map<String, String>): EncodingProfileConfigurationProperties =
            Binder(
                MapConfigurationPropertySource(values),
            ).bind("processor-profiles", Bindable.of(EncodingProfileConfigurationProperties::class.java)).get()

        expect("indexed configuration binds ordered Japanese rules and explicit fallback") {
            val configuration =
                bind(
                    mapOf(
                        "processor-profiles.fallback-profile" to "標準",
                        "processor-profiles.rules[0].id" to "first",
                        "processor-profiles.rules[0].profile" to "日本語 profile",
                        "processor-profiles.rules[0].channel" to "BS1",
                        "processor-profiles.rules[0].min-duration-seconds" to "100",
                        "processor-profiles.rules[1].id" to "second",
                        "processor-profiles.rules[1].profile" to "later",
                        "processor-profiles.rules[1].title-pattern" to "番組",
                    ),
                )
            val selector = EncodingProfileSelector(configuration, tools)
            selector.select(EncodingProfileInput("BS1", "局", "番組", 100.0)).ruleId shouldBe "first"
            selector.select(EncodingProfileInput("BS2", "局", "その他", 100.0)).profile shouldBe "標準"
        }
        expect("invalid bound rules and missing fallback fail before pipeline construction") {
            shouldThrow<Exception> {
                bind(
                    mapOf(
                        "processor-profiles.rules[0].id" to "invalid",
                        "processor-profiles.rules[0].profile" to "profile",
                        "processor-profiles.rules[0].title-pattern" to "[",
                    ),
                )
            }
            val configuration =
                bind(
                    mapOf(
                        "processor-profiles.rules[0].id" to "valid",
                        "processor-profiles.rules[0].profile" to "profile",
                        "processor-profiles.rules[0].channel" to "BS1",
                    ),
                )
            shouldThrow<IllegalArgumentException> { EncodingProfileSelector(configuration, tools) }
        }
    })
