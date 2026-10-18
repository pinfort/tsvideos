package me.pinfort.tsvideos.core.external.tool

import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.core.domain.VideoResolution
import me.pinfort.tsvideos.core.external.shell.ShellClient
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File

@Component
class VideoResolutionProbeClient(
    // Like DurationProbeClient, ffprobe is cross-platform so this calls ShellClient directly.
    private val shellClient: ShellClient,
    private val processorToolConfigurationProperties: ProcessorToolConfigurationProperties,
    private val logger: Logger,
) {
    // The resolution is supplementary metadata: ordinary probe failures (including non-video files) return null;
    // interruption must propagate to stop the invocation.
    fun probe(
        file: File,
        timeoutSec: Long = 60,
    ): VideoResolution? =
        try {
            val result =
                shellClient.executeCapturingOutput(
                    file.parentFile,
                    listOf(
                        processorToolConfigurationProperties.ffprobePath,
                        "-v",
                        "error",
                        "-select_streams",
                        "v:0",
                        "-show_entries",
                        "stream=width,height",
                        "-of",
                        "csv=p=0",
                        file.absolutePath,
                    ),
                    timeoutSec,
                )
            if (result.exitCode != 0) {
                logger.warn("ffprobe exited with non-zero code, file=$file, exitCode=${result.exitCode}, stderr=${result.stderr}")
                null
            } else {
                parse(result.stdout) ?: run {
                    logger.info("No video resolution found, file=$file, stdout=${result.stdout}")
                    null
                }
            }
        } catch (e: Exception) {
            if (ProcessingCancellation.isInterrupted(e)) throw e
            logger.warn("Failed to probe resolution, file=$file", e)
            null
        }

    private fun parse(stdout: String): VideoResolution? {
        val parts =
            stdout
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.split(",") ?: return null
        if (parts.size < 2) return null
        val width = parts[0].trim().toIntOrNull() ?: return null
        val height = parts[1].trim().toIntOrNull() ?: return null
        return if (width > 0 && height > 0) VideoResolution(width, height) else null
    }
}
