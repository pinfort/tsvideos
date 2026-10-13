package me.pinfort.tsvideos.manager.api.controller

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import me.pinfort.tsvideos.core.command.BulkLibraryCommand
import me.pinfort.tsvideos.core.domain.BulkAction
import me.pinfort.tsvideos.core.domain.BulkOutcome
import me.pinfort.tsvideos.core.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.core.domain.BulkPlan
import me.pinfort.tsvideos.core.domain.BulkRequest
import me.pinfort.tsvideos.core.domain.BulkResult
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

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
        it("maps invalid input to 400") {
            every { command.preview(any()) } throws IllegalArgumentException("Select IDs")
            mvc
                .perform(
                    post(
                        "/api/v1/programs/bulk/preview",
                    ).contentType(MediaType.APPLICATION_JSON).content("""{"ids":[],"action":"DELETE"}"""),
                ).andExpect(status().isBadRequest)
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
