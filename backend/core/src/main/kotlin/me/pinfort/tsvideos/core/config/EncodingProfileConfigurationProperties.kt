package me.pinfort.tsvideos.core.config

import me.pinfort.tsvideos.core.domain.EncodingProfilePolicy
import me.pinfort.tsvideos.core.domain.EncodingProfileRule
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("processor-profiles")
data class EncodingProfileConfigurationProperties(
    val rules: List<EncodingProfileRule> = emptyList(),
    val fallbackProfile: String? = null,
) {
    fun policy(amatsukaze: ProcessorToolConfigurationProperties.Amatsukaze): EncodingProfilePolicy =
        EncodingProfilePolicy(rules, fallbackProfile, amatsukaze.defaultProfile, amatsukaze.atxDivProfile)
}
