package me.pinfort.tsvideos.manager.api.controller

import jakarta.servlet.http.HttpServletRequest
import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.manager.api.response.VideoChaptersResponse
import me.pinfort.tsvideos.manager.api.stream.NasVideoResource
import me.pinfort.tsvideos.manager.api.stream.ResponseResources
import me.pinfort.tsvideos.storage.api.NasComponent
import me.pinfort.tsvideos.storage.api.NasStorageException
import org.springframework.core.io.support.ResourceRegion
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MimeType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class VideoFileController(
    private val createdFileCatalog: CreatedFileCatalog,
    private val nasComponent: NasComponent,
) {
    /** Chapters from the DB only; the NAS is not touched. 404 for anything [stream] would not play. */
    @GetMapping("/api/v1/video/{id}/chapters")
    fun chapters(
        @PathVariable(name = "id") id: Long,
    ): ResponseEntity<VideoChaptersResponse> {
        val chapters = createdFileCatalog.streamableVideoChapters(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(VideoChaptersResponse.of(chapters))
    }

    @GetMapping("/api/v1/video/{id}/stream")
    fun stream(
        @PathVariable(name = "id") id: Long,
        @RequestHeader headers: HttpHeaders,
        request: HttpServletRequest,
    ): ResponseEntity<ResourceRegion> {
        val video = createdFileCatalog.findStreamableVideo(id) ?: return ResponseEntity.notFound().build()
        // The body is written after this method returns, so the handle is closed when the request completes.
        val resources = ResponseResources.of(request)
        val handle =
            try {
                resources.adopt { nasComponent.openForRead(video.store, video.path) }
            } catch (e: NasStorageException) {
                // Any NAS failure (unreachable share, missing file, rejected login) is a 404.
                return ResponseEntity.notFound().build()
            }
        val resource = NasVideoResource(handle)
        val contentType = MediaType.asMediaType(MimeType.valueOf(CreatedFile.MP4_MIME))
        val contentLength = resource.contentLength()

        val (status, region) =
            try {
                val range = headers.range.firstOrNull()
                if (range == null) {
                    HttpStatus.OK to ResourceRegion(resource, 0, contentLength)
                } else {
                    HttpStatus.PARTIAL_CONTENT to range.toResourceRegion(resource)
                }
            } catch (e: IllegalArgumentException) {
                return ResponseEntity
                    .status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */$contentLength")
                    .build()
            }

        return ResponseEntity
            .status(status)
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .contentType(contentType)
            .body(region)
    }
}
