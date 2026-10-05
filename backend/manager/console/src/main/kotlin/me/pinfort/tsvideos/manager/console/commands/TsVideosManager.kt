package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import me.pinfort.tsvideos.core.version.ApplicationVersion
import org.springframework.stereotype.Component

@Component
class TsVideosManager(
    private val search: Search,
    private val get: Get,
    private val delete: Delete,
    private val modify: Modify,
    private val duplicates: Duplicates,
) : CliktCommand(name = "tvmcli") {
    init {
        subcommands(search, get, delete, modify, duplicates)
        versionOption(ApplicationVersion.value)
    }

    override fun run() = Unit
}
