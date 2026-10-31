package me.pinfort.tsvideos.manager.api.controller

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.library.api.DuplicateReviewCommand
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import me.pinfort.tsvideos.library.domain.DuplicateGroupIdentity
import me.pinfort.tsvideos.library.domain.DuplicateKeepCriterion
import me.pinfort.tsvideos.library.domain.DuplicateRecommendation
import me.pinfort.tsvideos.library.domain.DuplicateReview
import me.pinfort.tsvideos.library.domain.DuplicateReviewGroup
import me.pinfort.tsvideos.library.domain.DuplicateReviewMember
import me.pinfort.tsvideos.library.domain.StaleDuplicateGroupException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(DuplicateReviewController::class)
class DuplicateReviewControllerTest : DescribeSpec() {
    override val extensions = listOf(SpringExtension())

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean lateinit var command: DuplicateReviewCommand

    init {
        beforeEach { clearMocks(command) }

        it("reads duplicate review without changing programs, leaving hidden groups out unless asked") {
            every { command.review(any()) } returns DuplicateReview(emptyList())
            mockMvc.perform(get("/api/v1/programs/duplicates")).andExpect(status().isOk).andExpect(jsonPath("$.groups").isEmpty)
            verify(exactly = 1) { command.review(false) }
            mockMvc.perform(get("/api/v1/programs/duplicates?includeHidden=true")).andExpect(status().isOk)
            verify(exactly = 1) { command.review(true) }
            mockMvc.perform(get("/api/v1/programs/duplicates?includeHidden=maybe")).andExpect(status().isBadRequest)
            verify(exactly = 0) { command.hide(any()) }
            verify(exactly = 0) { command.restore(any()) }
        }

        it("hides and restores the named group and reports it with sorted members") {
            every { command.hide(any()) } just Runs
            every { command.restore(any()) } just Runs
            mockMvc
                .perform(
                    post("/api/v1/programs/duplicates/hide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reason":"SAME_BROADCAST","programIds":[3,1,2]}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.reason").value("SAME_BROADCAST"))
                .andExpect(jsonPath("$.programIds[0]").value(1))
                .andExpect(jsonPath("$.programIds[2]").value(3))
                .andExpect(jsonPath("$.hidden").value(true))
            verify(exactly = 1) { command.hide(DuplicateGroupIdentity(DuplicateGroup.Reason.SAME_BROADCAST, listOf(3, 1, 2))) }
            mockMvc
                .perform(
                    post("/api/v1/programs/duplicates/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"reason":"OVERLAP","programIds":[1,2]}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.hidden").value(false))
            verify(exactly = 1) { command.restore(DuplicateGroupIdentity(DuplicateGroup.Reason.OVERLAP, listOf(1, 2))) }
        }

        it("rejects malformed bodies before the command and maps invalid and stale groups") {
            listOf(
                """{"programIds":[1,2]}""",
                """{"reason":"UNKNOWN","programIds":[1,2]}""",
                """{"reason":"OVERLAP"}""",
                """{"reason":"OVERLAP","programIds":[1,null]}""",
                """{"reason":"OVERLAP","programIds":"1,2"}""",
                "not json",
            ).forEach { body ->
                listOf("hide", "restore").forEach { path ->
                    mockMvc
                        .perform(post("/api/v1/programs/duplicates/$path").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest)
                }
            }
            verify(exactly = 0) { command.hide(any()) }
            verify(exactly = 0) { command.restore(any()) }
            val body = """{"reason":"SAME_BROADCAST","programIds":[1,2]}"""
            every { command.hide(any()) } throws IllegalArgumentException("Program IDs must not repeat")
            mockMvc
                .perform(post("/api/v1/programs/duplicates/hide").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
            every { command.restore(any()) } throws StaleDuplicateGroupException()
            mockMvc
                .perform(post("/api/v1/programs/duplicates/restore").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict)
        }

        it("serializes a populated group in the shape the web page reads") {
            fun program(
                id: Long,
                drops: Int,
            ) = Program(id, "p$id", id, Program.Status.COMPLETED, drops, 100, LocalDateTime.of(2026, 1, 1, 0, 0), "ch", "t", "CH", 60.0)
            val file = CreatedFile(7, 3, "a/b.mp4", 100, "video/mp4", null, CreatedFile.Status.FILE_MOVED)
            every { command.review(false) } returns
                DuplicateReview(
                    listOf(
                        DuplicateReviewGroup(
                            DuplicateGroup.Reason.SAME_BROADCAST,
                            1,
                            DuplicateRecommendation(
                                DuplicateKeepCriterion.entries,
                                DuplicateKeepCriterion.DROPS,
                                2,
                                null,
                                Program.Status.COMPLETED,
                                60.0,
                                100,
                                1,
                            ),
                            listOf(DuplicateReviewMember(program(1, -1), listOf(file)), DuplicateReviewMember(program(2, 3), emptyList())),
                        ),
                    ),
                )
            mockMvc
                .perform(get("/api/v1/programs/duplicates"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.groups[0].reason").value("SAME_BROADCAST"))
                .andExpect(jsonPath("$.groups[0].recommendedId").value(1))
                .andExpect(jsonPath("$.groups[0].recommendation.orderedCriteria[0]").value("DROPS"))
                .andExpect(jsonPath("$.groups[0].recommendation.decisiveCriterion").value("DROPS"))
                .andExpect(jsonPath("$.groups[0].recommendation.comparedWithProgramId").value(2))
                .andExpect(jsonPath("$.groups[0].recommendation.drops").isEmpty)
                .andExpect(jsonPath("$.groups[0].recommendation.durationSeconds").value(60.0))
                .andExpect(jsonPath("$.groups[0].recommendation.recordedBytes").value(100))
                .andExpect(jsonPath("$.groups[0].members[0].program.id").value(1))
                .andExpect(jsonPath("$.groups[0].members[0].program.drops").value(-1))
                .andExpect(jsonPath("$.groups[0].members[0].availableFiles[0].id").value(7))
                .andExpect(jsonPath("$.groups[0].members[0].availableFiles[0].status").value("FILE_MOVED"))
                .andExpect(jsonPath("$.groups[0].members[1].availableFiles").isEmpty)
                .andExpect(jsonPath("$.groups[0].hidden").value(false))
        }
    }
}
