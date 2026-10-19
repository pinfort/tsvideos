package me.pinfort.tsvideos.manager.api.stream

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import jakarta.servlet.AsyncEvent
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import org.springframework.mock.web.MockAsyncContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.IOException

class ResponseResourcesTest :
    ExpectSpec({
        class Recorder(
            private val name: String,
            private val log: MutableList<String>,
            private val failure: Exception? = null,
        ) : AutoCloseable {
            override fun close() {
                log += name
                failure?.let { throw it }
            }
        }

        context("ResponseResources") {
            expect("closes every adopted resource once, newest first, even when one close fails") {
                val log = mutableListOf<String>()
                val resources = ResponseResources()
                resources.adopt { Recorder("first", log) }
                resources.adopt { Recorder("second", log, IllegalStateException("close failed")) }
                resources.adopt { Recorder("third", log) }

                resources.close()
                resources.close()

                log shouldBe listOf("third", "second", "first")
            }

            expect("a failed open adopts nothing") {
                val resources = ResponseResources()

                shouldThrow<IllegalStateException> { resources.adopt<AutoCloseable> { error("open failed") } }

                resources.close()
            }

            expect("refuses to open after the request completed") {
                val resources = ResponseResources()
                resources.close()
                var opened = false

                shouldThrow<IllegalStateException> {
                    resources.adopt {
                        opened = true
                        AutoCloseable {}
                    }
                }
                opened shouldBe false
            }

            expect("a resource opened while the request completes is closed once and refused") {
                val log = mutableListOf<String>()
                val resources = ResponseResources()

                val thrown =
                    shouldThrow<IllegalStateException> {
                        resources.adopt {
                            resources.close()
                            Recorder("raced", log)
                        }
                    }
                resources.close()

                thrown.message shouldBe "Request already completed"
                thrown.suppressed.toList() shouldBe emptyList()
                log shouldBe listOf("raced")
            }

            expect("a failing close of a raced resource is kept as suppressed, not as the failure") {
                val log = mutableListOf<String>()
                val resources = ResponseResources()
                val closeFailure = IOException("close failed")

                val thrown =
                    shouldThrow<IllegalStateException> {
                        resources.adopt {
                            resources.close()
                            Recorder("raced", log, closeFailure)
                        }
                    }
                resources.close()

                thrown.message shouldBe "Request already completed"
                thrown.suppressed.toList() shouldBe listOf(closeFailure)
                log shouldBe listOf("raced")
            }
        }

        context("ResponseResourcesFilter") {
            expect("installs resources for the request and closes them after the chain, then removes them") {
                val log = mutableListOf<String>()
                val request = MockHttpServletRequest()
                var duringChain: ResponseResources? = null

                ResponseResourcesFilter().doFilter(
                    request,
                    MockHttpServletResponse(),
                    FilterChain { req, _ ->
                        val resources = ResponseResources.of(req as HttpServletRequest)
                        duringChain = resources
                        resources.adopt { Recorder("handle", log) }
                        log shouldBe emptyList()
                    },
                )

                log shouldBe listOf("handle")
                shouldThrow<IllegalStateException> { ResponseResources.of(request) }
                shouldThrow<IllegalStateException> { duringChain!!.adopt { Recorder("late", log) } }
                log shouldBe listOf("handle")
            }

            expect("closes the resources when the chain fails") {
                val log = mutableListOf<String>()
                val failure = IOException("Broken pipe")

                val thrown =
                    shouldThrow<IOException> {
                        ResponseResourcesFilter().doFilter(
                            MockHttpServletRequest(),
                            MockHttpServletResponse(),
                            FilterChain { req, _ ->
                                ResponseResources.of(req as HttpServletRequest).adopt { Recorder("handle", log) }
                                throw failure
                            },
                        )
                    }

                thrown shouldBe failure
                log shouldBe listOf("handle")
            }

            context("async requests") {
                val log = mutableListOf<String>()
                lateinit var request: MockHttpServletRequest
                lateinit var response: MockHttpServletResponse

                fun startAsyncRequest(): MockAsyncContext {
                    ResponseResourcesFilter().doFilter(
                        request,
                        response,
                        FilterChain { req, res ->
                            ResponseResources.of(req as HttpServletRequest).adopt { Recorder("handle", log) }
                            req.startAsync(req, res)
                        },
                    )
                    return request.asyncContext as MockAsyncContext
                }

                beforeEach {
                    log.clear()
                    request = MockHttpServletRequest().apply { isAsyncSupported = true }
                    response = MockHttpServletResponse()
                }

                expect("keeps the resources installed and open for async dispatches until the request completes") {
                    val async = startAsyncRequest()

                    log shouldBe emptyList()
                    // An async dispatch (the filter is skipped for it) still finds and can use the same resources.
                    ResponseResources.of(request).adopt { Recorder("dispatch", log) }

                    async.complete()

                    log shouldBe listOf("dispatch", "handle")
                    shouldThrow<IllegalStateException> { ResponseResources.of(request) }
                }

                expect("a timeout or error does not release early; the completion that follows does") {
                    val async = startAsyncRequest()

                    async.listeners.toList().forEach { it.onTimeout(AsyncEvent(async)) }
                    async.listeners.toList().forEach { it.onError(AsyncEvent(async, IOException("Broken pipe"))) }

                    log shouldBe emptyList()
                    ResponseResources.of(request).adopt { Recorder("error handling", log) }

                    async.complete()

                    log shouldBe listOf("error handling", "handle")
                    shouldThrow<IllegalStateException> { ResponseResources.of(request) }
                }

                expect("a restarted async cycle keeps waiting for the completion of the new cycle") {
                    val first = startAsyncRequest()
                    val second = request.startAsync(request, response) as MockAsyncContext
                    // The container drops the listeners of the finished cycle after notifying them of the restart.
                    first.listeners.toList().forEach { it.onStartAsync(AsyncEvent(second)) }

                    log shouldBe emptyList()
                    ResponseResources.of(request)

                    second.complete()

                    log shouldBe listOf("handle")
                    shouldThrow<IllegalStateException> { ResponseResources.of(request) }
                }

                expect("a repeated completion notification releases only once") {
                    val async = startAsyncRequest()
                    val listeners = async.listeners.toList()

                    async.complete()
                    listeners.forEach { it.onComplete(AsyncEvent(async)) }

                    log shouldBe listOf("handle")
                }

                expect("an async request completed before the chain returns is released right after the chain") {
                    ResponseResourcesFilter().doFilter(
                        request,
                        response,
                        FilterChain { req, res ->
                            ResponseResources.of(req as HttpServletRequest).adopt { Recorder("handle", log) }
                            req.startAsync(req, res).complete()
                            log shouldBe emptyList()
                        },
                    )

                    log shouldBe listOf("handle")
                    (request.asyncContext as MockAsyncContext).listeners shouldBe emptyList()
                    shouldThrow<IllegalStateException> { ResponseResources.of(request) }
                }
            }

            expect("the controller fails fast when the filter is not installed") {
                shouldThrow<IllegalStateException> { ResponseResources.of(MockHttpServletRequest()) }
            }
        }
    })
