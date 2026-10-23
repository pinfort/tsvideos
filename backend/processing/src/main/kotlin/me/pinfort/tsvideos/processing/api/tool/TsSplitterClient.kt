package me.pinfort.tsvideos.processing.api.tool

import me.pinfort.tsvideos.processing.api.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.processing.api.shell.ShellComponent
import org.springframework.stereotype.Component
import java.io.File

@Component
class TsSplitterClient(
    private val shellComponent: ShellComponent,
    private val processorToolConfigurationProperties: ProcessorToolConfigurationProperties,
) {
    fun split(
        inFile: File,
        outDir: File,
        timeoutSec: Long,
    ): Int =
        shellComponent.executeOnWindows(
            inFile.parentFile,
            listOf(
                processorToolConfigurationProperties.tsSplitterPath,
                "-SD",
                "-EIT",
                "-1SEG",
                "-OUT",
                outDir.absolutePath,
                "-SEP",
                inFile.absolutePath,
            ),
            timeoutSec,
        )
}
