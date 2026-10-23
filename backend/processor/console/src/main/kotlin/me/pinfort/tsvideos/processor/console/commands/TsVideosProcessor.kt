package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import me.pinfort.tsvideos.console.common.version.ApplicationVersion
import org.springframework.stereotype.Component

@Component
class TsVideosProcessor(
    private val process: ProcessCommand,
    private val afterEncode: AfterEncodeCommand,
    private val reset: ResetCommand,
    private val releaseClaim: ReleaseClaimCommand,
    private val status: StatusCommand,
    private val history: HistoryCommand,
    private val quality: QualityCommand,
) : CliktCommand(name = "tvpcli") {
    init {
        subcommands(process, afterEncode, reset, releaseClaim, status, history, quality)
        versionOption(ApplicationVersion.value)
    }

    override fun help(context: Context): String =
        "Process recordings. Use tvpcli preflight [--json] [--dry-run | --check-write] <paths> to check prerequisites without processing."

    override fun run() = Unit
}
