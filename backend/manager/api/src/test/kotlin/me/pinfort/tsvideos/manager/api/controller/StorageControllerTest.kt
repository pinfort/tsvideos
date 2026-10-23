package me.pinfort.tsvideos.manager.api.controller

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.mockk.verify
import me.pinfort.tsvideos.library.api.StorageOverviewCommand
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import me.pinfort.tsvideos.library.domain.LibraryStatsTotals
import me.pinfort.tsvideos.library.domain.StorageDuplicateCandidate
import me.pinfort.tsvideos.library.domain.StorageOverview
import me.pinfort.tsvideos.library.domain.StorageRecording
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(StorageController::class)
class StorageControllerTest : DescribeSpec() {
    override val extensions = listOf(SpringExtension())

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean lateinit var command: StorageOverviewCommand

    init {
        it("returns shared totals and null physical usage") {
            every { command.overview() } returns
                StorageOverview(
                    LibraryStatsTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    emptyList(),
                    emptyList(),
                    0,
                    0,
                )
            mockMvc
                .perform(get("/api/v1/storage"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.totals.registeredBytes").value(0))
                .andExpect(jsonPath("$.totals.verifiedPhysicalBytes").isEmpty)
                .andExpect(jsonPath("$.largestRecordings").isEmpty)
                .andExpect(jsonPath("$.verifiedReclaimableBytes").isEmpty)
            verify(exactly = 1) { command.overview() }
        }
        it("serializes recordings and duplicate candidates") {
            val kept = StorageRecording(1, "kept", 3000000000)
            val other = StorageRecording(2, "other", 100)
            every { command.overview() } returns
                StorageOverview(
                    LibraryStatsTotals(2, 0, 0, 0, 2, 3000000100, 0, 3000000100, 0, 3000000100, 0),
                    listOf(kept, other),
                    listOf(StorageDuplicateCandidate(DuplicateGroup.Reason.OVERLAP, kept, listOf(other))),
                    100,
                    1,
                )
            mockMvc
                .perform(get("/api/v1/storage"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.largestRecordings[0].registeredBytes").value(3000000000))
                .andExpect(jsonPath("$.duplicateCandidates[0].reason").value("OVERLAP"))
                .andExpect(jsonPath("$.duplicateCandidates[0].retained.id").value(1))
                .andExpect(jsonPath("$.duplicateCandidates[0].removable[0].name").value("other"))
                .andExpect(jsonPath("$.estimatedReclaimableBytes").value(100))
                .andExpect(jsonPath("$.excludedSharedPathCount").value(1))
        }
        it("has no write operation") {
            mockMvc.perform(post("/api/v1/storage")).andExpect(status().isMethodNotAllowed)
        }
    }
}
