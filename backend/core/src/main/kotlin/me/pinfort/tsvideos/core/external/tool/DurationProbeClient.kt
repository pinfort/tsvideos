package me.pinfort.tsvideos.core.external.tool

import me.pinfort.tsvideos.core.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.core.external.shell.ShellClient
import org.slf4j.Logger
import org.springframework.stereotype.Component
import java.io.File

@Component
class DurationProbeClient(
    // ffprobe is cross-platform, unlike the Windows-only tools, so this deliberately bypasses ShellComponent's
    // Windows guard and calls ShellClient directly.
    private val shellClient: ShellClient,
    private val processorToolConfigurationProperties: ProcessorToolConfigurationProperties,
    private val logger: Logger,
) {
    // never throws; any failure (non-zero exit, malformed output, timeout, missing binary) is swallowed to 0.0
    fun probe(
        file: File,
        timeoutSec: Long = 60,
    ): Double =
        try {
            val result =
                shellClient.executeCapturingOutput(
                    file.parentFile,
                    listOf(
                        processorToolConfigurationProperties.ffprobePath,
                        "-v",
                        "error",
                        "-show_entries",
                        "format=duration",
                        "-of",
                        "default=noprint_wrappers=1:nokey=1",
                        file.absolutePath,
                    ),
                    timeoutSec,
                )
            if (result.exitCode != 0) {
                logger.warn("ffprobe exited with non-zero code, file=$file, exitCode=${result.exitCode}, stderr=${result.stderr}")
                0.0
            } else {
                result.stdout.trim().toDoubleOrNull() ?: run {
                    logger.warn("ffprobe returned unparseable duration, file=$file, stdout=${result.stdout}")
                    0.0
                }
            }
        } catch (e: Exception) {
            if (me.pinfort.tsvideos.core.component.ProcessingCancellation
                    .isInterrupted(e)
            ) {
                throw e
            }
            logger.warn("Failed to probe duration, file=$file", e)
            0.0
        }
}
