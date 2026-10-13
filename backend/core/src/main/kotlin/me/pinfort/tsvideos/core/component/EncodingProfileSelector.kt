package me.pinfort.tsvideos.core.component

import me.pinfort.tsvideos.core.config.EncodingProfileConfigurationProperties
import me.pinfort.tsvideos.core.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.core.domain.EncodingProfileInput
import me.pinfort.tsvideos.core.domain.EncodingProfileSelection
import org.springframework.stereotype.Component

/** Constructing the bean validates the complete policy before pipeline side effects. */
@Component
class EncodingProfileSelector(
    configuration: EncodingProfileConfigurationProperties,
    tools: ProcessorToolConfigurationProperties,
) {
    private val policy = configuration.policy(tools.amatsukaze)

    fun select(input: EncodingProfileInput): EncodingProfileSelection = policy.select(input)
}
