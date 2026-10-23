package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import me.pinfort.tsvideos.console.common.version.ApplicationVersion
import org.springframework.stereotype.Component

@Component
class TsVideosManager(
    private val search: Search,
    private val get: Get,
    private val delete: Delete,
    private val modify: Modify,
    private val duplicates: Duplicates,
    private val doctor: Doctor,
    private val stats: Stats,
    private val verify: Verify,
    private val cleanup: Cleanup,
) : CliktCommand(name = "tvmcli") {
    init {
        subcommands(search, get, delete, modify, duplicates, doctor, stats, verify, cleanup)
        versionOption(ApplicationVersion.value)
    }

    override fun run() = Unit
}
