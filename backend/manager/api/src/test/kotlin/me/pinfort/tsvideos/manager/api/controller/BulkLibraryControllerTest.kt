package me.pinfort.tsvideos.manager.api.controller

import com.ninjasquad.springmockk.MockkBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import jakarta.servlet.ServletException
import me.pinfort.tsvideos.catalog.api.CreatedFileSnapshot
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.library.api.BulkLibraryCommand
import me.pinfort.tsvideos.library.domain.BulkAction
import me.pinfort.tsvideos.library.domain.BulkOutcome
import me.pinfort.tsvideos.library.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.library.domain.BulkPlan
import me.pinfort.tsvideos.library.domain.BulkRequest
import me.pinfort.tsvideos.library.domain.BulkResult
import me.pinfort.tsvideos.library.domain.BulkSnapshot
import me.pinfort.tsvideos.library.domain.BulkTarget
import me.pinfort.tsvideos.library.domain.InvalidBulkRequestException
import org.hamcrest.Matchers.aMapWithSize
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(BulkLibraryController::class)
class BulkLibraryControllerTest : DescribeSpec() {
    override val extensions = listOf(SpringExtension())

    @Autowired lateinit var mvc: MockMvc

    @MockkBean lateinit var command: BulkLibraryCommand

    init {
        it("returns a read-only preview") {
            every { command.preview(any()) } returns BulkPlan(BulkRequest(listOf(1), BulkAction.DELETE), emptyList())
            mvc
                .perform(
                    post(
                        "/api/v1/programs/bulk/preview",
                    ).contentType(MediaType.APPLICATION_JSON).content("""{"ids":[1],"action":"DELETE"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.originalSourcePreserved").value(true))
        }
        // The preview publishes the stored rows; the frontend reads these property names and enum values.
        it("publishes snapshot rows with their stored property names and enum values") {
            val recordedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
            val program = Program(1, "name", 10, Program.Status.COMPLETED, 0, 12, recordedAt, "1", "title", "channel", 12.0)
            val executed =
                ExecutedFile(10, "C:\\original.m2ts", 0, 12, recordedAt, "1", "title", "channel", 12.0, ExecutedFile.Status.SPLITTED)
            val snapshot =
                BulkSnapshot(
                    program,
                    executed,
                    listOf(SplittedFile(20, 10, "C:\\split.m2ts", 12, 12.0, SplittedFile.Status.ENCODE_TASK_ADDED)),
                    listOf(
                        CreatedFileSnapshot(30, 20, "base/a/alpha/video.mp4", 12, null, null, CreatedFile.Status.FILE_MOVED, null, null),
                    ),
                )
            val target = BulkTarget(1, snapshot, "f".repeat(64), emptyMap(), true, null, 12)
            every { command.preview(any()) } returns BulkPlan(BulkRequest(listOf(1), BulkAction.DELETE), listOf(target))
            mvc
                .perform(
                    post(
                        "/api/v1/programs/bulk/preview",
                    ).contentType(MediaType.APPLICATION_JSON).content("""{"ids":[1],"action":"DELETE"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.targets[0].snapshot", aMapWithSize<String, Any>(4)))
                .andExpect(jsonPath("$.targets[0].snapshot.executed", aMapWithSize<String, Any>(10)))
                .andExpect(jsonPath("$.targets[0].snapshot.executed.channelName").value("channel"))
                .andExpect(jsonPath("$.targets[0].snapshot.executed.status").value("SPLITTED"))
                .andExpect(jsonPath("$.targets[0].snapshot.splitFiles[0]", aMapWithSize<String, Any>(6)))
                .andExpect(jsonPath("$.targets[0].snapshot.splitFiles[0].executedFileId").value(10))
                .andExpect(jsonPath("$.targets[0].snapshot.splitFiles[0].status").value("ENCODE_TASK_ADDED"))
                .andExpect(jsonPath("$.targets[0].snapshot.createdFiles[0]", aMapWithSize<String, Any>(9)))
                .andExpect(jsonPath("$.targets[0].snapshot.createdFiles[0].splittedFileId").value(20))
                .andExpect(jsonPath("$.targets[0].snapshot.createdFiles[0].status").value("FILE_MOVED"))
                .andExpect(jsonPath("$.targets[0].snapshot.createdFiles[0]", hasKey("mime")))
                .andExpect(jsonPath("$.targets[0].snapshot.createdFiles[0]", hasKey("width")))
                .andExpect(jsonPath("$.targets[0].snapshot.createdFiles[0]", not(hasKey("mp4"))))
        }
        it("maps invalid input to 400") {
            every { command.preview(any()) } throws InvalidBulkRequestException("Select IDs")
            mvc
                .perform(
                    post(
                        "/api/v1/programs/bulk/preview",
                    ).contentType(MediaType.APPLICATION_JSON).content("""{"ids":[],"action":"DELETE"}"""),
                ).andExpect(status().isBadRequest)
        }
        it("does not report an internal IllegalArgumentException as a client error") {
            every { command.preview(any()) } throws java.nio.file.InvalidPathException("bad", "internal detail")
            shouldThrow<ServletException> {
                mvc.perform(
                    post(
                        "/api/v1/programs/bulk/preview",
                    ).contentType(MediaType.APPLICATION_JSON).content("""{"ids":[1],"action":"DELETE"}"""),
                )
            }
        }
        it("returns per-recording outcomes including partial failures") {
            every { command.apply(any()) } returns
                BulkResult(
                    listOf(BulkOutcome(1, BulkOutcomeStatus.SUCCESS, "done"), BulkOutcome(2, BulkOutcomeStatus.STALE, "preview again")),
                )
            mvc
                .perform(
                    post(
                        "/api/v1/programs/bulk/apply",
                    ).contentType(
                        MediaType.APPLICATION_JSON,
                    ).content("""{"request":{"ids":[1,2],"action":"DELETE"},"fingerprints":{"1":"a","2":"b"}}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.outcomes[1].status").value("STALE"))
        }
    }
}
