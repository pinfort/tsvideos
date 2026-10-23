package me.pinfort.tsvideos.processing.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "processor-tool")
data class ProcessorToolConfigurationProperties(
    val tsSplitterPath: String,
    val amatsukazeAddTaskPath: String,
    val ffprobePath: String,
    val amatsukaze: Amatsukaze,
) {
    data class Amatsukaze(
        val host: String,
        val port: Int,
        val defaultProfile: String,
        val atxDivProfile: String,
    )
}
