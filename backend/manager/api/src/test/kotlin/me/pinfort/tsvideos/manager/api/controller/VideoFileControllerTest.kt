package me.pinfort.tsvideos.manager.api.controller

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.catalog.api.StreamableVideo
import me.pinfort.tsvideos.catalog.domain.VideoChapter
import me.pinfort.tsvideos.catalog.domain.VideoChapters
import me.pinfort.tsvideos.manager.api.stream.FakeReadHandle
import me.pinfort.tsvideos.manager.api.stream.ResponseResourcesFilter
import me.pinfort.tsvideos.storage.api.NasAccessException
import me.pinfort.tsvideos.storage.api.NasAuthenticationException
import me.pinfort.tsvideos.storage.api.NasComponent
import me.pinfort.tsvideos.storage.api.NasReadHandle
import me.pinfort.tsvideos.storage.api.NasStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

// Runs the real filter chain, controller and ResourceRegionHttpMessageConverter, so the handle's lifetime is observed
// across body conversion and not just until the controller returns.
@WebMvcTest(VideoFileController::class)
class VideoFileControllerTest : DescribeSpec() {
    override val extensions = listOf(SpringExtension())

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var responseResourcesFilter: ResponseResourcesFilter

    @MockkBean
    lateinit var createdFileCatalog: CreatedFileCatalog

    @MockkBean
    lateinit var nasComponent: NasComponent

    init {
        val bytes = ByteArray(10) { it.toByte() }
        val path = "a\\bucket\\video.mp4"

        fun video(handle: NasReadHandle): NasReadHandle {
            every { createdFileCatalog.findStreamableVideo(1L) } returns StreamableVideo(1L, NasStore.VIDEO_STORE_NAS, path)
            every { nasComponent.openForRead(NasStore.VIDEO_STORE_NAS, path) } returns handle
            return handle
        }

        fun FakeReadHandle.shouldBeReleasedOnce() {
            closes shouldBe 1
            openStreams shouldBe 0
        }

        describe("chapters") {
            it("returns the probed chapters in order, titles as stored, without touching the NAS") {
                every { createdFileCatalog.streamableVideoChapters(1L) } returns
                    VideoChapters(
                        true,
                        listOf(VideoChapter(0, 0.0, 5.739, "A"), VideoChapter(1, 5.739, 60.0, "A"), VideoChapter(2, 60.0, 65.5, null)),
                    )

                mockMvc
                    .perform(get("/api/v1/video/1/chapters"))
                    .andExpect(status().isOk)
                    .andExpect(
                        content().json(
                            """
                            {"probed":true,"chapters":[
                              {"index":0,"startSeconds":0.0,"endSeconds":5.739,"title":"A"},
                              {"index":1,"startSeconds":5.739,"endSeconds":60.0,"title":"A"},
                              {"index":2,"startSeconds":60.0,"endSeconds":65.5,"title":null}
                            ]}
                            """,
                            JsonCompareMode.STRICT,
                        ),
                    )

                verify(exactly = 0) { nasComponent.openForRead(any(), any()) }
            }

            it("distinguishes a file probed with no chapters from one never probed") {
                every { createdFileCatalog.streamableVideoChapters(1L) } returns VideoChapters(true, emptyList())
                every { createdFileCatalog.streamableVideoChapters(2L) } returns VideoChapters.NOT_PROBED

                mockMvc
                    .perform(get("/api/v1/video/1/chapters"))
                    .andExpect(status().isOk)
                    .andExpect(content().json("""{"probed":true,"chapters":[]}""", JsonCompareMode.STRICT))
                mockMvc
                    .perform(get("/api/v1/video/2/chapters"))
                    .andExpect(status().isOk)
                    .andExpect(content().json("""{"probed":false,"chapters":[]}""", JsonCompareMode.STRICT))
            }

            it("not found when the video is not streamable") {
                every { createdFileCatalog.streamableVideoChapters(1L) } returns null

                mockMvc
                    .perform(get("/api/v1/video/1/chapters"))
                    .andExpect(status().isNotFound)

                verify(exactly = 0) { nasComponent.openForRead(any(), any()) }
            }
        }

        describe("stream") {
            it("writes the whole file with 200 and closes the handle once, after the body is written") {
                val handle = FakeReadHandle(bytes).also { video(it) }

                mockMvc
                    .perform(get("/api/v1/video/1/stream"))
                    .andExpect(status().isOk)
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "10"))
                    .andExpect(content().bytes(bytes))

                // The handle was still open when the converter opened the stream (a closed fake refuses to open).
                handle.opens shouldBe 1
                handle.shouldBeReleasedOnce()
                verify(exactly = 0) { nasComponent.openForRead(NasStore.ORIGINAL_STORE_NAS, any()) }
            }

            it("range request returns partial content") {
                val handle = FakeReadHandle(bytes).also { video(it) }

                mockMvc
                    .perform(get("/api/v1/video/1/stream").header(HttpHeaders.RANGE, "bytes=2-5"))
                    .andExpect(status().isPartialContent)
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/10"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "4"))
                    .andExpect(content().bytes(bytes.copyOfRange(2, 6)))

                handle.shouldBeReleasedOnce()
            }

            it("open-ended range request returns bytes to end of file") {
                val handle = FakeReadHandle(bytes).also { video(it) }

                mockMvc
                    .perform(get("/api/v1/video/1/stream").header(HttpHeaders.RANGE, "bytes=8-"))
                    .andExpect(status().isPartialContent)
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 8-9/10"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "2"))

                handle.shouldBeReleasedOnce()
            }

            it("only the first of several ranges is returned") {
                val handle = FakeReadHandle(bytes).also { video(it) }

                mockMvc
                    .perform(get("/api/v1/video/1/stream").header(HttpHeaders.RANGE, "bytes=0-1,4-5"))
                    .andExpect(status().isPartialContent)
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-1/10"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "2"))
                    .andExpect(content().bytes(bytes.copyOfRange(0, 2)))

                handle.shouldBeReleasedOnce()
            }

            it("repeated requests each open and release their own handle") {
                val first = FakeReadHandle(bytes)
                val second = FakeReadHandle(bytes)
                every { createdFileCatalog.findStreamableVideo(1L) } returns StreamableVideo(1L, NasStore.VIDEO_STORE_NAS, path)
                every { nasComponent.openForRead(NasStore.VIDEO_STORE_NAS, path) } returnsMany listOf(first, second)

                repeat(2) {
                    mockMvc
                        .perform(get("/api/v1/video/1/stream").header(HttpHeaders.RANGE, "bytes=0-3"))
                        .andExpect(status().isPartialContent)
                }

                listOf(first, second).forEach {
                    it.opens shouldBe 1
                    it.shouldBeReleasedOnce()
                }
            }

            it("out-of-range Range header returns 416 without opening a stream, and releases the handle") {
                val handle = FakeReadHandle(bytes).also { video(it) }

                mockMvc
                    .perform(get("/api/v1/video/1/stream").header(HttpHeaders.RANGE, "bytes=10000-"))
                    .andExpect(status().isRequestedRangeNotSatisfiable)
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */10"))

                handle.opens shouldBe 0
                handle.shouldBeReleasedOnce()
            }

            it("malformed Range header returns 416 and releases the handle") {
                val handle = FakeReadHandle(bytes).also { video(it) }

                mockMvc
                    .perform(get("/api/v1/video/1/stream").header(HttpHeaders.RANGE, "pages=1-2"))
                    .andExpect(status().isRequestedRangeNotSatisfiable)
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */10"))

                handle.opens shouldBe 0
                handle.shouldBeReleasedOnce()
            }

            it("not found when the catalog has no streamable video; the NAS is not touched") {
                every { createdFileCatalog.findStreamableVideo(1L) } returns null

                mockMvc
                    .perform(get("/api/v1/video/1/stream"))
                    .andExpect(status().isNotFound)

                verify(exactly = 0) { nasComponent.openForRead(any(), any()) }
            }

            it("not found when the NAS cannot open the file or rejects the login") {
                every { createdFileCatalog.findStreamableVideo(1L) } returns StreamableVideo(1L, NasStore.VIDEO_STORE_NAS, path)
                listOf(
                    NasAccessException("NAS open for reading failed", IOException("unreachable")),
                    NasAuthenticationException("NAS open for reading failed: credentials rejected", IOException("denied")),
                ).forEach { failure ->
                    every { nasComponent.openForRead(NasStore.VIDEO_STORE_NAS, path) } throws failure

                    mockMvc
                        .perform(get("/api/v1/video/1/stream"))
                        .andExpect(status().isNotFound)
                }
            }

            it("a failure that is not a storage failure is not turned into 404") {
                every { createdFileCatalog.findStreamableVideo(1L) } returns StreamableVideo(1L, NasStore.VIDEO_STORE_NAS, path)
                every { nasComponent.openForRead(NasStore.VIDEO_STORE_NAS, path) } throws IllegalStateException("boom")

                val outcome =
                    runCatching {
                        mockMvc
                            .perform(get("/api/v1/video/1/stream"))
                            .andReturn()
                            .response.status
                    }

                (outcome.getOrNull() == 404) shouldBe false
            }

            it("releases the handle when the controller fails after opening it") {
                val handle =
                    mockk<NasReadHandle> {
                        every { length } throws IllegalStateException("length unavailable")
                        every { close() } returns Unit
                    }
                video(handle)

                runCatching { mockMvc.perform(get("/api/v1/video/1/stream")) }

                verify(exactly = 1) { handle.close() }
                verify(exactly = 0) { handle.open() }
            }

            it("releases the handle when opening the body stream fails during conversion") {
                val handle = FakeReadHandle.failingOpen(bytes).also { video(it) }

                runCatching { mockMvc.perform(get("/api/v1/video/1/stream")) }

                handle.opens shouldBe 1
                handle.shouldBeReleasedOnce()
            }

            it("releases the handle and the stream when writing fails because the client disconnected or cancelled") {
                val handle = FakeReadHandle(ByteArray(64 * 1024)).also { video(it) }
                val disconnecting =
                    MockMvcBuilders
                        .webAppContextSetup(context)
                        .addFilters<DefaultMockMvcBuilder>(
                            responseResourcesFilter,
                            DisconnectAfter(1024),
                        ).build()

                val outcome =
                    runCatching {
                        disconnecting
                            .perform(get("/api/v1/video/1/stream"))
                            .andReturn()
                            .response.contentAsByteArray.size
                    }

                // However the failure is reported, the full body was not delivered and nothing was leaked.
                (outcome.getOrNull() == 64 * 1024) shouldBe false
                handle.opens shouldBe 1
                handle.shouldBeReleasedOnce()
            }
        }
    }

    /** Fails every write after [limit] bytes, as a servlet container does once the client has gone away. */
    private class DisconnectAfter(
        private val limit: Int,
    ) : OncePerRequestFilter() {
        override fun doFilterInternal(
            request: HttpServletRequest,
            response: HttpServletResponse,
            filterChain: FilterChain,
        ) {
            val output =
                object : ServletOutputStream() {
                    private var written = 0

                    override fun write(b: Int) {
                        if (written++ >= limit) throw IOException("Broken pipe")
                        response.outputStream.write(b)
                    }

                    override fun isReady(): Boolean = true

                    override fun setWriteListener(listener: WriteListener?) {}
                }
            filterChain.doFilter(
                request,
                object : HttpServletResponseWrapper(response) {
                    override fun getOutputStream(): ServletOutputStream = output
                },
            )
        }
    }
}
