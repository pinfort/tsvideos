package me.pinfort.tsvideos.manager.api.controller

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.mockk.verify
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.core.command.DuplicateReviewCommand
import me.pinfort.tsvideos.core.domain.DuplicateGroup
import me.pinfort.tsvideos.core.domain.DuplicateKeepCriterion
import me.pinfort.tsvideos.core.domain.DuplicateRecommendation
import me.pinfort.tsvideos.core.domain.DuplicateReview
import me.pinfort.tsvideos.core.domain.DuplicateReviewGroup
import me.pinfort.tsvideos.core.domain.DuplicateReviewMember
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(DuplicateReviewController::class)
class DuplicateReviewControllerTest : DescribeSpec() {
    override val extensions = listOf(SpringExtension())

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean lateinit var command: DuplicateReviewCommand

    init {
        it("reads duplicate review without changing programs") {
            every { command.review() } returns DuplicateReview(emptyList())
            mockMvc.perform(get("/api/v1/programs/duplicates")).andExpect(status().isOk).andExpect(jsonPath("$.groups").isEmpty)
            verify(exactly = 1) { command.review() }
        }

        it("serializes a populated group in the shape the web page reads") {
            fun program(
                id: Long,
                drops: Int,
            ) = Program(id, "p$id", id, Program.Status.COMPLETED, drops, 100, LocalDateTime.of(2026, 1, 1, 0, 0), "ch", "t", "CH", 60.0)
            val file = CreatedFile(7, 3, "a/b.mp4", 100, "video/mp4", null, CreatedFile.Status.FILE_MOVED)
            every { command.review() } returns
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
        }
    }
}
