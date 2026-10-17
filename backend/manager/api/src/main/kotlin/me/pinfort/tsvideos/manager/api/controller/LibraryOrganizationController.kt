package me.pinfort.tsvideos.manager.api.controller

import me.pinfort.tsvideos.core.command.LibraryOrganizationCommand
import me.pinfort.tsvideos.core.domain.DuplicateLibraryNameException
import me.pinfort.tsvideos.core.domain.LibraryItemNotFoundException
import me.pinfort.tsvideos.core.domain.LibraryKind
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class LibraryNameRequest(
    val name: String,
)

@RestController
class LibraryOrganizationController(
    private val command: LibraryOrganizationCommand,
) {
    @GetMapping("/api/v1/library/{kind}")
    fun list(
        @PathVariable kind: String,
    ) = respond { command.list(kind(kind)) }

    @GetMapping("/api/v1/library/{kind}/{id}")
    fun find(
        @PathVariable kind: String,
        @PathVariable id: Long,
    ) = respond { command.find(kind(kind), id) }

    @PostMapping("/api/v1/library/{kind}")
    fun create(
        @PathVariable kind: String,
        @RequestBody body: LibraryNameRequest,
    ) = respond { command.create(kind(kind), body.name) }

    @PutMapping("/api/v1/library/{kind}/{id}")
    fun rename(
        @PathVariable kind: String,
        @PathVariable id: Long,
        @RequestBody body: LibraryNameRequest,
    ) = respond { command.rename(kind(kind), id, body.name) }

    @DeleteMapping("/api/v1/library/{kind}/{id}")
    fun delete(
        @PathVariable kind: String,
        @PathVariable id: Long,
    ) = respond {
        command.delete(kind(kind), id)
        mapOf("ok" to true)
    }

    @GetMapping("/api/v1/programs/{programId}/organization")
    fun forProgram(
        @PathVariable programId: Long,
    ) = respond { command.forProgram(programId) }

    @PutMapping("/api/v1/library/{kind}/{id}/programs/{programId}")
    fun assign(
        @PathVariable kind: String,
        @PathVariable id: Long,
        @PathVariable programId: Long,
    ) = respond {
        command.assign(kind(kind), id, programId, true)
        mapOf(
            "ok" to true,
        )
    }

    @DeleteMapping("/api/v1/library/{kind}/{id}/programs/{programId}")
    fun unassign(
        @PathVariable kind: String,
        @PathVariable id: Long,
        @PathVariable programId: Long,
    ) = respond {
        command.assign(kind(kind), id, programId, false)
        mapOf(
            "ok" to true,
        )
    }

    @PostMapping("/api/v1/programs/{programId}/library-tags")
    fun assignTag(
        @PathVariable programId: Long,
        @RequestBody body: LibraryNameRequest,
    ) = respond { command.assignTag(programId, body.name) }

    private fun kind(value: String) =
        when (value) {
            "collections" -> LibraryKind.COLLECTION
            "tags" -> LibraryKind.TAG
            else -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown library kind")
        }

    private fun <T> respond(action: () -> T): T =
        try {
            action()
        } catch (_: LibraryItemNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library item not found")
        } catch (_: DuplicateLibraryNameException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Name already exists")
        } catch (error: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
        } catch (_: PessimisticLockingFailureException) {
            // Lock waits/deadlocks with concurrent label or recording changes; InnoDB rolls one side back and
            // the client may simply retry.
            throw ResponseStatusException(HttpStatus.CONFLICT, "Concurrent change, retry the request")
        }
}
