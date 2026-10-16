package me.pinfort.tsvideos.manager.api.controller

import me.pinfort.tsvideos.core.command.BulkLibraryCommand
import me.pinfort.tsvideos.core.domain.BulkApplyRequest
import me.pinfort.tsvideos.core.domain.BulkRequest
import me.pinfort.tsvideos.core.domain.InvalidBulkRequestException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class BulkLibraryController(
    private val command: BulkLibraryCommand,
) {
    @PostMapping("/api/v1/programs/bulk/preview")
    fun preview(
        @RequestBody request: BulkRequest,
    ) = validated { command.preview(request) }

    @PostMapping("/api/v1/programs/bulk/apply")
    fun apply(
        @RequestBody request: BulkApplyRequest,
    ) = validated { command.apply(request) }

    private fun <T> validated(action: () -> T): T =
        try {
            action()
        } catch (error: InvalidBulkRequestException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
        }
}
