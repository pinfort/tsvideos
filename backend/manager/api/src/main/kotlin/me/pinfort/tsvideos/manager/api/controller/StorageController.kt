package me.pinfort.tsvideos.manager.api.controller

import me.pinfort.tsvideos.library.api.StorageOverviewCommand
import me.pinfort.tsvideos.manager.api.response.StorageOverviewResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class StorageController(
    private val command: StorageOverviewCommand,
) {
    @GetMapping("/api/v1/storage")
    fun overview(): StorageOverviewResponse = StorageOverviewResponse.of(command.overview())
}
