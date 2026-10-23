package me.pinfort.tsvideos.processing.api.tool

import me.pinfort.tsvideos.processing.api.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.processing.api.shell.ShellComponent
import org.springframework.stereotype.Component
import java.io.File

@Component
class AmatsukazeAddTaskClient(
    private val shellComponent: ShellComponent,
    private val processorToolConfigurationProperties: ProcessorToolConfigurationProperties,
) {
    // the submission result is not checked; queueing is fire-and-forget, matching the original tool's behavior
    fun addTask(
        file: File,
        outDir: File,
        profile: String,
    ): Int {
        val amatsukaze = processorToolConfigurationProperties.amatsukaze
        return shellComponent.executeOnWindows(
            file.parentFile,
            listOf(
                processorToolConfigurationProperties.amatsukazeAddTaskPath,
                "-f",
                file.absolutePath,
                "-ip",
                amatsukaze.host,
                "-p",
                amatsukaze.port.toString(),
                "-o",
                outDir.absolutePath,
                "-s",
                profile,
                "--priority",
                "3",
                "--no-move",
            ),
        )
    }
}
