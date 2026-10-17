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
                    PROBE_TIMEOUT_SECONDS,
                )
            if (result.exitCode != 0) {
                logger.warn("ffprobe stream inspection exited with {}; streams unavailable", result.exitCode)
                null
            } else {
                parse(result.stdout)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failure: Exception) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Stream inspection interrupted")
            // Visible at WARN: a missing or misconfigured ffprobe must not pass silently as an "unavailable" measurement.
            logger.warn("ffprobe stream inspection failed (check FFPROBE_PATH); streams unavailable", failure)
            null
        }

    internal fun parse(json: String): Set<RecordingStream>? {
        val streams = json.let(JSON::readTree).get("streams") ?: return null
        if (!streams.isArray) return null
        val result = mutableSetOf<RecordingStream>()
        for (stream in streams) {
            // A stream without a codec_type means the output is not what we expect, so the whole result is unavailable;
            // known-but-unsupported types (data, attachment, unknown) are simply ignored below.
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
        // Stream headers only, not a full read; a timeout is reported as an unavailable measurement.
        const val PROBE_TIMEOUT_SECONDS = 60L
        val JSON: JsonMapper = JsonMapper.builder().build()
        val logger: Logger = LoggerFactory.getLogger(RecordingStreamProbeClient::class.java)
    }
}
