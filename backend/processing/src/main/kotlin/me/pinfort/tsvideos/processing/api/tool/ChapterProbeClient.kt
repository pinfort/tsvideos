package me.pinfort.tsvideos.processing.api.tool

import me.pinfort.tsvideos.catalog.domain.VideoChapter
import me.pinfort.tsvideos.processing.api.ProcessingCancellation
import me.pinfort.tsvideos.processing.api.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.processing.api.shell.ShellClient
import org.slf4j.Logger
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File

/** Reads the chapters embedded in a video (Amatsukaze writes join_logo_scp chapters into its mp4 output). */
@Component
class ChapterProbeClient(
    // Like VideoResolutionProbeClient, ffprobe is cross-platform so this calls ShellClient directly.
    private val shellClient: ShellClient,
    private val processorToolConfigurationProperties: ProcessorToolConfigurationProperties,
    private val logger: Logger,
) {
    /**
     * The normalized chapters of [file]; an empty list when it has none. Chapters are supplementary metadata, so an
     * ordinary failure (non-zero exit, unexpected JSON, timeout, ffprobe missing) returns null, meaning "not probed";
     * interruption propagates to stop the invocation.
     */
    fun probe(
        file: File,
        timeoutSec: Long = 60,
    ): List<VideoChapter>? =
        try {
            val result =
                shellClient.executeCapturingOutput(
                    file.parentFile,
                    listOf(
                        processorToolConfigurationProperties.ffprobePath,
                        "-v",
                        "error",
                        "-show_chapters",
                        "-of",
                        "json",
                        file.absolutePath,
                    ),
                    timeoutSec,
                )
            if (result.exitCode != 0) {
                logger.warn(
                    "ffprobe exited with non-zero code, file=$file, exitCode=${result.exitCode}, stderr=${bounded(result.stderr)}",
                )
                null
            } else {
                parse(result.stdout) ?: run {
                    logger.warn("Unexpected ffprobe chapter output, file=$file, stdout=${bounded(result.stdout)}")
                    null
                }
            }
        } catch (e: Exception) {
            if (ProcessingCancellation.isInterrupted(e)) throw e
            logger.warn("Failed to probe chapters, file=$file", e)
            null
        }

    /**
     * Keeps chapters whose times are finite, non-negative and end >= start, sorts them by start (stable, so equal starts
     * keep ffprobe's order) and renumbers them from 0. The title is kept exactly as ffprobe reports it.
     */
    internal fun parse(json: String): List<VideoChapter>? {
        val output =
            try {
                JSON.readTree(json)
            } catch (_: JacksonException) {
                return null
            }
        val chapters = output?.get("chapters") ?: return null
        if (!chapters.isArray) return null
        return chapters
            .mapNotNull { chapter ->
                // Non-local return: a non-object entry means the output's structure is malformed, so the whole probe
                // fails, unlike an entry with invalid timestamps, which is just dropped below.
                if (!chapter.isObject) return null
                val start = seconds(chapter.get("start_time")) ?: return@mapNotNull null
                val end = seconds(chapter.get("end_time")) ?: return@mapNotNull null
                if (start < 0 || end < start) return@mapNotNull null
                val title =
                    chapter
                        .get("tags")
                        ?.get("title")
                        ?.takeIf { it.isString }
                        ?.asString()
                Triple(start, end, title)
            }.sortedBy { it.first }
            .mapIndexed { index, (start, end, title) -> VideoChapter(index, start, end, title) }
    }

    // ffprobe writes times as decimal strings ("5.739000"); a number is accepted too.
    private fun seconds(node: JsonNode?): Double? {
        val value =
            when {
                node == null -> null
                node.isString -> node.asString().trim().toDoubleOrNull()
                node.isNumber -> node.asDouble()
                else -> null
            }
        return value?.takeIf { it.isFinite() }
    }

    // Keeps diagnostic tool output in logs bounded; a corrupt file can make ffprobe print arbitrarily much.
    private fun bounded(output: String): String =
        if (output.length <= MAX_LOGGED_OUTPUT) output else "${output.take(MAX_LOGGED_OUTPUT)}...(truncated, ${output.length} chars)"

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
        const val MAX_LOGGED_OUTPUT = 500
    }
}
