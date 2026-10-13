package me.pinfort.tsvideos.manager.api.controller

import me.pinfort.tsvideos.core.command.HealthScanAlreadyRunning
import me.pinfort.tsvideos.core.command.LibraryHealthCommand
import me.pinfort.tsvideos.core.command.LibraryHealthScanCommand
import me.pinfort.tsvideos.core.domain.LibraryHealth
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class LibraryHealthController(
    private val health: LibraryHealthCommand,
    private val scanner: LibraryHealthScanCommand,
) {
    @GetMapping("/api/v1/health/library")
    fun get(): LibraryHealth = health.read()

    @PostMapping("/api/v1/health/library/verify")
    fun verify(): LibraryHealth {
        try {
            scanner.scan()
        } catch (
            _: HealthScanAlreadyRunning,
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Verification is already running")
        }
        return health.read()
    }
}
