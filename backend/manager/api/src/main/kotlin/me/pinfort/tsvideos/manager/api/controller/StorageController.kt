package me.pinfort.tsvideos.manager.api.controller

import me.pinfort.tsvideos.core.command.StorageOverviewCommand
import me.pinfort.tsvideos.core.domain.StorageOverview
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class StorageController(
    private val command: StorageOverviewCommand,
) {
    @GetMapping("/api/v1/storage")
    fun overview(): StorageOverview = command.overview()
}
