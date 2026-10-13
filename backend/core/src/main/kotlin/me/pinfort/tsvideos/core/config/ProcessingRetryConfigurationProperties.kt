package me.pinfort.tsvideos.core.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("processor-retry")
data class ProcessingRetryConfigurationProperties(
    val maxAttempts: Int = 3,
    val initialDelayMillis: Long = 250,
    val maxDelayMillis: Long = 5000,
) {
    init {
        require(maxAttempts in 1..10) { "Retry attempts must be 1..10" }
        require(initialDelayMillis in 1..60000 && maxDelayMillis in initialDelayMillis..60000) { "Invalid retry delay range" }
    }
}
