package me.pinfort.tsvideos.manager.api.controller

import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramSearchQuery
import me.pinfort.tsvideos.manager.api.exception.ProgramNotFoundException
import me.pinfort.tsvideos.manager.api.response.ProgramDetailResponse
import me.pinfort.tsvideos.manager.api.response.SearchResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

@RestController
class ProgramController(
    private val programCommand: ProgramCommand,
) {
    @GetMapping("/api/v1/programs")
    fun index(
        @RequestParam(name = "name", required = false) name: String?,
        @RequestParam(name = "channel", required = false) channel: String?,
        @RequestParam(name = "recordedFrom", required = false) recordedFrom: String?,
        @RequestParam(name = "recordedTo", required = false) recordedTo: String?,
        @RequestParam(name = "status", required = false) status: Program.Status?,
        @RequestParam(name = "minDrops", required = false) minDrops: Int?,
        @RequestParam(name = "maxDrops", required = false) maxDrops: Int?,
        @RequestParam(name = "fileType", required = false) fileType: ProgramSearchQuery.FileType?,
        @RequestParam(name = "sort", defaultValue = "RECORDED_AT") sort: ProgramSearchQuery.Sort,
        @RequestParam(name = "direction", defaultValue = "DESC") direction: ProgramSearchQuery.Direction,
        @RequestParam(name = "limit", defaultValue = "100") limit: Int,
        @RequestParam(name = "offset", defaultValue = "0") offset: Int,
        @RequestParam(name = "collectionId", required = false) collectionId: Long?,
        @RequestParam(name = "libraryTagId", required = false) libraryTagId: Long?,
    ): SearchResponse {
        val query =
            try {
                ProgramSearchQuery(
                    name,
                    channel,
                    parseLocalDateTime(recordedFrom),
                    parseLocalDateTime(recordedTo),
                    status,
                    minDrops,
                    maxDrops,
                    fileType,
                    sort,
                    direction,
                    limit,
                    offset,
                    collectionId,
                    libraryTagId,
                )
            } catch (_: DateTimeParseException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "recording timestamps must be ISO local date-times")
            } catch (error: IllegalArgumentException) {
                // Only ProgramSearchQuery's init-block `require`s can throw here; their messages are static and safe to return.
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
            }
        val page = programCommand.search(query)
        return SearchResponse(page.items, page.total, page.limit, page.offset)
    }

    private fun parseLocalDateTime(value: String?): LocalDateTime? = value?.takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse)

    @GetMapping("/api/v1/programs/{id}")
    fun get(
        @PathVariable(name = "id") id: Long,
    ): ProgramDetailResponse {
        val program = programCommand.find(id) ?: throw ProgramNotFoundException("program not found. id=$id")
        val programDetail = programCommand.findDetail(id) ?: throw ProgramNotFoundException("program not found. id=$id")
        val videoFiles = programCommand.videoFiles(program)
        return ProgramDetailResponse(
            program = programDetail,
            videoFiles = videoFiles,
        )
    }

    @DeleteMapping("/api/v1/programs/{id}")
    fun delete(
        @PathVariable(name = "id") id: Long,
    ): ResponseEntity<Unit> = ResponseEntity(HttpStatus.NOT_IMPLEMENTED)
}
