package me.pinfort.tsvideos.manager.api.controller

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.mockk.verify
import me.pinfort.tsvideos.core.command.DuplicateReviewCommand
import me.pinfort.tsvideos.core.domain.DuplicateReview
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

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
    }
}
