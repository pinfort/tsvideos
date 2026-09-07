package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import me.pinfort.tsvideos.core.version.ApplicationVersion
import org.springframework.stereotype.Component

@Component
class TsVideosProcessor(
    private val process: ProcessCommand,
    private val afterEncode: AfterEncodeCommand,
    private val reset: ResetCommand,
) : CliktCommand(name = "tvpcli") {
    init {
        subcommands(process, afterEncode, reset)
        versionOption(ApplicationVersion.value)
    }

    override fun run() = Unit
}
