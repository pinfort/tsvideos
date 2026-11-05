package me.pinfort.tsvideos.manager.api.controller

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import me.pinfort.tsvideos.library.api.DuplicateDeletionCommand
import me.pinfort.tsvideos.library.domain.BulkAction
import me.pinfort.tsvideos.library.domain.BulkOutcome
import me.pinfort.tsvideos.library.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.library.domain.BulkRequest
import me.pinfort.tsvideos.library.domain.BulkResult
import me.pinfort.tsvideos.library.domain.DuplicateDeletionApplyRequest
import me.pinfort.tsvideos.library.domain.DuplicateDeletionGroup
import me.pinfort.tsvideos.library.domain.DuplicateDeletionPlan
import me.pinfort.tsvideos.library.domain.DuplicateDeletionPlanGroup
import me.pinfort.tsvideos.library.domain.DuplicateDeletionRequest
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import me.pinfort.tsvideos.library.domain.InvalidBulkRequestException
import me.pinfort.tsvideos.library.domain.StaleDuplicateGroupException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(DuplicateDeletionController::class)
class DuplicateDeletionControllerTest : DescribeSpec() {
    override val extensions = listOf(SpringExtension())

    @Autowired lateinit var mvc: MockMvc

    @MockkBean lateinit var command: DuplicateDeletionCommand

    private val fingerprint = "a".repeat(64)

    private fun send(
        path: String,
        body: String,
    ) = mvc.perform(post("/api/v1/programs/duplicates/delete/$path").contentType(MediaType.APPLICATION_JSON).content(body))

    init {
        beforeEach { clearMocks(command) }

        it("previews the named groups and returns the deletion and kept snapshots") {
            every { command.preview(any()) } returns
                DuplicateDeletionPlan(
                    BulkRequest(listOf(2), BulkAction.DELETE),
                    listOf(DuplicateDeletionPlanGroup(DuplicateGroup.Reason.REBROADCAST, listOf(1, 2), listOf(2), listOf(1))),
                    emptyList(),
                    emptyList(),
                )
            send("preview", """{"groups":[{"reason":"REBROADCAST","programIds":[2,1],"deleteIds":[2]}]}""")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.request.ids[0]").value(2))
                .andExpect(jsonPath("$.request.action").value("DELETE"))
                .andExpect(jsonPath("$.groups[0].keptIds[0]").value(1))
                .andExpect(jsonPath("$.keptTargets").isArray)
                .andExpect(jsonPath("$.originalSourcePreserved").value(true))
            verify(exactly = 1) {
                command.preview(
                    DuplicateDeletionRequest(listOf(DuplicateDeletionGroup(DuplicateGroup.Reason.REBROADCAST, listOf(2, 1), listOf(2)))),
                )
            }
        }

        it("applies with the target and kept fingerprints and returns the outcomes") {
            every { command.apply(any()) } returns BulkResult(listOf(BulkOutcome(2, BulkOutcomeStatus.STALE, "changed")))
            send(
                "apply",
                """{"groups":[{"reason":"OVERLAP","programIds":[1,2],"deleteIds":[2]}],""" +
                    """"fingerprints":{"2":"$fingerprint"},"keptFingerprints":{"1":"$fingerprint"}}""",
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.outcomes[0].status").value("STALE"))
            verify(exactly = 1) {
                command.apply(
                    DuplicateDeletionApplyRequest(
                        listOf(DuplicateDeletionGroup(DuplicateGroup.Reason.OVERLAP, listOf(1, 2), listOf(2))),
                        mapOf(2L to fingerprint),
                        mapOf(1L to fingerprint),
                    ),
                )
            }
        }

        it("rejects malformed bodies before the command") {
            listOf(
                """{"groups":[{"reason":"UNKNOWN","programIds":[1,2],"deleteIds":[2]}]}""",
                """{"groups":[{"reason":"OVERLAP","programIds":[1,null],"deleteIds":[2]}]}""",
                """{"groups":[{"reason":"OVERLAP","programIds":[1,2],"deleteIds":[null]}]}""",
                """{"groups":[null]}""",
                """{"groups":[{"reason":"OVERLAP","programIds":[1,2]}]}""",
                "not json",
            ).forEach { send("preview", it).andExpect(status().isBadRequest) }
            send(
                "apply",
                """{"groups":[{"reason":"OVERLAP","programIds":[1,2],"deleteIds":[2]}],"fingerprints":{"2":null},"keptFingerprints":{}}""",
            ).andExpect(status().isBadRequest)
            verify(exactly = 0) { command.preview(any()) }
            verify(exactly = 0) { command.apply(any()) }
        }

        it("maps an all-selected group to 400 and a group no longer detected to 409") {
            val body = """{"groups":[{"reason":"OVERLAP","programIds":[1,2],"deleteIds":[1,2]}]}"""
            every { command.preview(any()) } throws InvalidBulkRequestException("Keep at least one recording of each duplicate group")
            send("preview", body).andExpect(status().isBadRequest)
            every { command.preview(any()) } throws StaleDuplicateGroupException()
            send("preview", body).andExpect(status().isConflict)
        }
    }
}
