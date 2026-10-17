package me.pinfort.tsvideos.manager.api.controller

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import me.pinfort.tsvideos.core.command.HealthScanAlreadyRunning
import me.pinfort.tsvideos.core.command.LibraryHealthCommand
import me.pinfort.tsvideos.core.command.LibraryHealthScanCommand
import me.pinfort.tsvideos.core.domain.LibraryHealth
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(LibraryHealthController::class)
class LibraryHealthControllerTest : DescribeSpec() {
    override val extensions = listOf(SpringExtension())

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean lateinit var health: LibraryHealthCommand

    @MockkBean lateinit var scanner: LibraryHealthScanCommand

    init {
        it("GET only reads saved state and preserves unchecked null") {
            every { health.read() } returns LibraryHealth(emptyList(), emptyList(), null, null, emptyList(), null)
            mockMvc.perform(get("/api/v1/health/library")).andExpect(status().isOk).andExpect(jsonPath("$.summary").isEmpty)
            verify(exactly = 0) { scanner.scan() }
        }
        it("explicit POST triggers scan and returns refreshed state") {
            every { scanner.scan() } just Runs
            every { health.read() } returns LibraryHealth(emptyList(), emptyList(), null, null, emptyList(), null)
            mockMvc.perform(post("/api/v1/health/library/verify")).andExpect(status().isOk)
            verify(exactly = 1) { scanner.scan() }
        }
        it("concurrent active scan returns conflict") {
            every { scanner.scan() } throws HealthScanAlreadyRunning()
            mockMvc.perform(post("/api/v1/health/library/verify")).andExpect(status().isConflict)
        }
    }
}
