package me.pinfort.tsvideos.manager.api.controller

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramDetail
import me.pinfort.tsvideos.core.domain.ProgramSearchPage
import me.pinfort.tsvideos.core.domain.ProgramSearchQuery
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.nullValue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultHandlers.print
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(ProgramController::class)
class ProgramControllerTest : DescribeSpec() {
    override val extensions = listOf(SpringExtension())

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var programCommand: ProgramCommand

    private val program =
        Program(
            id = 1,
            name = "test",
            executedFileId = 2,
            status = Program.Status.COMPLETED,
            drops = 3,
            size = 4,
            duration = 5.0,
            recordedAt = LocalDateTime.MIN,
            channel = "",
            title = "",
            channelName = "",
        )

    private val programDetail =
        ProgramDetail(
            id = 1,
            name = "test",
            executedFileId = 2,
            status = Program.Status.COMPLETED,
            drops = 3,
            size = 4,
            duration = 5.0,
            recordedAt = LocalDateTime.MIN,
            channel = "",
            title = "",
            channelName = "",
            createdFiles = listOf(),
        )

    init {
        describe("index") {
            it("success") {
                every { programCommand.search(ProgramSearchQuery()) } returns ProgramSearchPage(emptyList(), 0, 100, 0)
                mockMvc
                    .perform(get("/api/v1/programs"))
                    .andDo(print())
                    .andExpect(status().isOk)
            }

            it("success with parameters") {
                every { programCommand.search(ProgramSearchQuery(name = "foo", limit = 10, offset = 5)) } returns
                    ProgramSearchPage(emptyList(), 3, 10, 5)
                mockMvc
                    .perform(get("/api/v1/programs").param("name", "foo").param("limit", "10").param("offset", "5"))
                    .andDo(print())
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.total").value(3))
                    .andExpect(jsonPath("$.limit").value(10))
                    .andExpect(jsonPath("$.offset").value(5))
            }
            it("combines every shared filter and preserves typed sorting") {
                val query =
                    ProgramSearchQuery(
                        name = "news",
                        channel = "BS1",
                        recordedFrom = LocalDateTime.parse("2026-10-01T00:00:00"),
                        recordedTo = LocalDateTime.parse("2026-11-01T00:00:00"),
                        status = Program.Status.COMPLETED,
                        minDrops = 0,
                        maxDrops = 2,
                        fileType = ProgramSearchQuery.FileType.ENCODED,
                        sort = ProgramSearchQuery.Sort.SIZE,
                        direction = ProgramSearchQuery.Direction.ASC,
                        limit = 500,
                        offset = 2,
                    )
                every { programCommand.search(query) } returns ProgramSearchPage(listOf(program), 10, 500, 2)
                mockMvc
                    .perform(
                        get("/api/v1/programs")
                            .param("name", "news")
                            .param("channel", "BS1")
                            .param("recordedFrom", "2026-10-01T00:00:00")
                            .param("recordedTo", "2026-11-01T00:00:00")
                            .param("status", "COMPLETED")
                            .param("minDrops", "0")
                            .param("maxDrops", "2")
                            .param("fileType", "ENCODED")
                            .param("sort", "SIZE")
                            .param("direction", "ASC")
                            .param("limit", "500")
                            .param("offset", "2"),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.programs[0].id").value(1))
                    .andExpect(jsonPath("$.total").value(10))
            }
            it("treats an empty recordedFrom as absent") {
                every { programCommand.search(ProgramSearchQuery()) } returns ProgramSearchPage(emptyList(), 0, 100, 0)
                mockMvc
                    .perform(get("/api/v1/programs").param("recordedFrom", ""))
                    .andExpect(status().isOk)
            }

            it("accepts recordedTo without recordedFrom") {
                val query = ProgramSearchQuery(recordedTo = LocalDateTime.parse("2026-11-01T00:00:00"))
                every { programCommand.search(query) } returns ProgramSearchPage(emptyList(), 0, 100, 0)
                mockMvc
                    .perform(get("/api/v1/programs").param("recordedTo", "2026-11-01T00:00:00"))
                    .andExpect(status().isOk)
            }

            listOf(
                mapOf("limit" to "0"),
                mapOf("limit" to "501"),
                mapOf("limit" to "1.5"),
                mapOf("offset" to "-1"),
                mapOf("minDrops" to "-1"),
                mapOf("maxDrops" to "-1"),
                mapOf("minDrops" to "3", "maxDrops" to "2"),
                mapOf("recordedFrom" to "2026-02-30T00:00:00"),
                mapOf("recordedFrom" to "2026-10-12T00:00:00Z"),
                mapOf("recordedFrom" to "2026-10-12T00:00:00", "recordedTo" to "2026-10-12T00:00:00"),
                mapOf("status" to "wrong"),
                mapOf("fileType" to "jpg"),
                mapOf("sort" to "name"),
                mapOf("direction" to "up"),
            ).forEach { params ->
                it("rejects $params with 400") {
                    val request = get("/api/v1/programs")
                    params.forEach { (key, value) -> request.param(key, value) }
                    mockMvc.perform(request).andExpect(status().isBadRequest)
                }
            }
        }

        it("passes collection and tag filters into shared search and rejects nonpositive IDs") {
            every { programCommand.search(match { it.collectionId == 3L && it.libraryTagId == 9L }) } returns
                ProgramSearchPage(emptyList(), 0, 100, 0)
            mockMvc
                .perform(get("/api/v1/programs").param("collectionId", "3").param("libraryTagId", "9"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.total").value(0))
            mockMvc.perform(get("/api/v1/programs").param("collectionId", "0")).andExpect(status().isBadRequest)
            mockMvc.perform(get("/api/v1/programs").param("libraryTagId", "-1")).andExpect(status().isBadRequest)
        }

        describe("detail") {
            it("success") {
                every { programCommand.find(1L) } returns program
                every { programCommand.findDetail(1L) } returns programDetail
                every { programCommand.videoFiles(program) } returns listOf()

                mockMvc
                    .perform(get("/api/v1/programs/1"))
                    .andDo(print())
                    .andExpect(status().isOk)
            }

            it("emits the resolution keys for every video file, null when unknown") {
                every { programCommand.find(1L) } returns program
                every { programCommand.findDetail(1L) } returns programDetail
                every { programCommand.videoFiles(program) } returns
                    listOf(
                        CreatedFile(1, 2, "a.mp4", 3, "video/mp4", null, CreatedFile.Status.FILE_MOVED, 1920, 1080),
                        CreatedFile(2, 2, "a.ass", 3, null, null, CreatedFile.Status.FILE_MOVED),
                    )

                mockMvc
                    .perform(get("/api/v1/programs/1"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.videoFiles[0].width").value(1920))
                    .andExpect(jsonPath("$.videoFiles[0].height").value(1080))
                    .andExpect(jsonPath("$.videoFiles[1]", hasKey("width")))
                    .andExpect(jsonPath("$.videoFiles[1].width").value(nullValue()))
                    .andExpect(jsonPath("$.videoFiles[1].height").value(nullValue()))
            }

            it("not found when find returns null") {
                every { programCommand.find(1L) } returns null

                mockMvc
                    .perform(get("/api/v1/programs/1"))
                    .andDo(print())
                    .andExpect(status().isNotFound)
            }

            it("not found when findDetail returns null") {
                every { programCommand.find(1L) } returns program
                every { programCommand.findDetail(1L) } returns null

                mockMvc
                    .perform(get("/api/v1/programs/1"))
                    .andDo(print())
                    .andExpect(status().isNotFound)
            }
        }

        describe("delete") {
            it("not implemented") {
                mockMvc
                    .perform(delete("/api/v1/programs/1"))
                    .andDo(print())
                    .andExpect(status().isNotImplemented)
            }
        }
    }
}
