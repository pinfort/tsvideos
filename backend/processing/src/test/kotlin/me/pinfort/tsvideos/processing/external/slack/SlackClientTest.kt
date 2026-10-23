package me.pinfort.tsvideos.processing.external.slack

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.Logger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class SlackClientTest :
    ExpectSpec({
        lateinit var logger: Logger
        lateinit var httpClient: HttpClient

        beforeTest {
            clearAllMocks()
            logger = mockk()
            httpClient = mockk()
        }

        context("notify") {
            expect("does nothing when the webhook url is blank") {
                every { logger.info(any<String>()) } just Runs

                val slackClient = SlackClient("", logger, httpClient)
                slackClient.notify("hello")

                verify(exactly = 0) { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<Void>>()) }
                verify { logger.info(any<String>()) }
            }

            expect("sends the message as a JSON payload to the webhook") {
                var receivedBody = ""
                var receivedContentType = ""
                val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
                server.createContext("/webhook") { exchange ->
                    receivedBody = exchange.requestBody.readBytes().decodeToString()
                    receivedContentType = exchange.requestHeaders.getFirst("Content-Type")
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
                server.start()

                try {
                    val slackClient = SlackClient("http://localhost:${server.address.port}/webhook", logger)
                    slackClient.notify("hello \"world\"\n")

                    receivedBody shouldBe "{\"text\":\"hello \\\"world\\\"\\n\"}"
                    receivedContentType shouldBe "application/json"
                } finally {
                    server.stop(0)
                }
            }

            expect("logs a warning when the webhook responds with a non-2xx status") {
                val response = mockk<HttpResponse<Void>>()
                every { response.statusCode() } returns 500
                every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<Void>>()) } returns response
                every { logger.warn(any<String>()) } just Runs

                val slackClient = SlackClient("http://localhost/webhook", logger, httpClient)
                slackClient.notify("hello")

                verify { logger.warn(any<String>()) }
            }

            expect("logs a warning instead of throwing when the request fails") {
                every {
                    httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<Void>>())
                } throws IOException("boom")
                every { logger.warn(any<String>(), any<Throwable>()) } just Runs

                val slackClient = SlackClient("http://localhost/webhook", logger, httpClient)
                slackClient.notify("hello")

                verify { logger.warn(any<String>(), any<Throwable>()) }
            }
        }
    })
