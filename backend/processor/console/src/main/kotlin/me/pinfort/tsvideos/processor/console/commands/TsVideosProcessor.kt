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
    private val releaseClaim: ReleaseClaimCommand,
    private val status: StatusCommand,
    private val history: HistoryCommand,
) : CliktCommand(name = "tvpcli") {
    init {
        subcommands(process, afterEncode, reset, releaseClaim, status, history)
        versionOption(ApplicationVersion.value)
    }

    override fun run() = Unit
}
