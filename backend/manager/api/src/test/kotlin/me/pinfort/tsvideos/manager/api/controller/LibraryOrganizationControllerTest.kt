package me.pinfort.tsvideos.manager.api.controller

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import me.pinfort.tsvideos.library.api.LibraryOrganizationCommand
import me.pinfort.tsvideos.library.domain.DuplicateLibraryNameException
import me.pinfort.tsvideos.library.domain.LibraryItemNotFoundException
import me.pinfort.tsvideos.library.domain.LibraryKind
import me.pinfort.tsvideos.library.domain.LibraryLabel
import me.pinfort.tsvideos.library.domain.ProgramOrganization
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(LibraryOrganizationController::class)
class LibraryOrganizationControllerTest : DescribeSpec() {
    override val extensions = listOf(SpringExtension())

    @Autowired lateinit var mvc: MockMvc

    @MockkBean lateinit var command: LibraryOrganizationCommand

    init {
        it("lists manual collections and persisted membership") {
            every { command.list(LibraryKind.COLLECTION) } returns listOf(LibraryLabel(3, "日本語シリーズ", 2))
            every { command.forProgram(7) } returns ProgramOrganization(listOf(LibraryLabel(3, "日本語シリーズ", 2)), emptyList())
            mvc.perform(get("/api/v1/library/collections")).andExpect(status().isOk).andExpect(jsonPath("$[0].name").value("日本語シリーズ"))
            mvc.perform(get("/api/v1/programs/7/organization")).andExpect(status().isOk).andExpect(jsonPath("$.collections[0].id").value(3))
        }
        it("creates and renames labels through the shared command") {
            every { command.create(LibraryKind.COLLECTION, "シリーズ") } returns LibraryLabel(3, "シリーズ", 0)
            every { command.rename(LibraryKind.COLLECTION, 3, "改名") } returns LibraryLabel(3, "改名", 0)
            mvc
                .perform(post("/api/v1/library/collections").contentType(MediaType.APPLICATION_JSON).content("""{"name":"シリーズ"}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(3))
            mvc
                .perform(put("/api/v1/library/collections/3").contentType(MediaType.APPLICATION_JSON).content("""{"name":"改名"}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("改名"))
        }
        it("returns parseable success for assignment removal and label deletion") {
            every { command.assign(LibraryKind.TAG, 3, 7, any()) } just Runs
            every { command.delete(LibraryKind.TAG, 3) } just Runs
            mvc.perform(put("/api/v1/library/tags/3/programs/7")).andExpect(status().isOk).andExpect(jsonPath("$.ok").value(true))
            mvc.perform(delete("/api/v1/library/tags/3/programs/7")).andExpect(status().isOk).andExpect(jsonPath("$.ok").value(true))
            mvc.perform(delete("/api/v1/library/tags/3")).andExpect(status().isOk).andExpect(jsonPath("$.ok").value(true))
            verify {
                command.assign(LibraryKind.TAG, 3, 7, true)
                command.assign(LibraryKind.TAG, 3, 7, false)
            }
        }
        it("maps duplicate invalid and missing targets or kinds to 409 400 and 404") {
            every { command.create(LibraryKind.TAG, "duplicate") } throws DuplicateLibraryNameException()
            every { command.create(LibraryKind.TAG, "") } throws IllegalArgumentException("Invalid name")
            every { command.find(LibraryKind.TAG, 99) } throws LibraryItemNotFoundException()
            mvc
                .perform(post("/api/v1/library/tags").contentType(MediaType.APPLICATION_JSON).content("""{"name":"duplicate"}"""))
                .andExpect(status().isConflict)
            mvc
                .perform(post("/api/v1/library/tags").contentType(MediaType.APPLICATION_JSON).content("""{"name":""}"""))
                .andExpect(status().isBadRequest)
            mvc.perform(get("/api/v1/library/tags/99")).andExpect(status().isNotFound)
            mvc.perform(get("/api/v1/library/broadcast-tags")).andExpect(status().isNotFound)
        }
        it("maps a lock conflict with a concurrent recording deletion to a retryable 409") {
            every { command.assign(LibraryKind.COLLECTION, 3, 7, true) } throws
                CannotAcquireLockException("deadlock")
            mvc.perform(put("/api/v1/library/collections/3/programs/7")).andExpect(status().isConflict)
        }
        it("creates or reuses a tag while assigning") {
            every { command.assignTag(7, "Favorite") } returns LibraryLabel(9, "Favorite", 1)
            mvc
                .perform(post("/api/v1/programs/7/library-tags").contentType(MediaType.APPLICATION_JSON).content("""{"name":"Favorite"}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(9))
        }
    }
}
