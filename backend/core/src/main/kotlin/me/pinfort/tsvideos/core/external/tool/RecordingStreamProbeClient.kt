package me.pinfort.tsvideos.core.external.tool

import me.pinfort.tsvideos.core.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.core.domain.RecordingStream
import me.pinfort.tsvideos.core.external.shell.ShellClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.io.File

@Component
class RecordingStreamProbeClient(
    private val shell: ShellClient,
    private val tools: ProcessorToolConfigurationProperties,
) {
    /** null means unavailable; an empty known set means no supported streams. */
    fun probe(file: File): Set<RecordingStream>? =
        try {
            val result =
                shell.executeCapturingOutput(
                    file.parentFile,
                    listOf(tools.ffprobePath, "-v", "error", "-show_entries", "stream=codec_type", "-of", "json", file.absolutePath),
                    60,
                )
            if (result.exitCode != 0) {
                logger.debug("ffprobe stream inspection exited with {}; streams unavailable", result.exitCode)
                null
            } else {
                parse(result.stdout)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Stream inspection interrupted")
            null
        }

    internal fun parse(json: String): Set<RecordingStream>? {
        val streams = json.let(JSON::readTree).get("streams") ?: return null
        if (!streams.isArray) return null
        val result = mutableSetOf<RecordingStream>()
        for (stream in streams) {
            val type = stream.get("codec_type") ?: return null
            if (!type.isString) return null
            when (type.asString()) {
                "audio" -> result += RecordingStream.AUDIO
                "video" -> result += RecordingStream.VIDEO
                "subtitle" -> result += RecordingStream.SUBTITLE
            }
        }
        return result
    }

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
        val logger: Logger = LoggerFactory.getLogger(RecordingStreamProbeClient::class.java)
    }
}
