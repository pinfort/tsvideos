package me.pinfort.tsvideos.processing.external.slack

import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
internal class SlackClient(
    @Value("\${SLACK_WEBHOOK_URL:}") private val webhookUrl: String,
    private val logger: Logger,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build(),
) {
    fun notify(message: String) {
        if (webhookUrl.isBlank()) {
            logger.info("SLACK_WEBHOOK_URL is not configured, skip notification")
            return
        }

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(message)))
                .build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() !in 200..299) {
                logger.warn("Slack notification failed, status=${response.statusCode()}")
            }
        } catch (e: IOException) {
            logger.warn("Slack notification failed", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Slack notification interrupted", e)
        }
    }

    // If the complexity increases, please replace it with Jackson
    private fun toJson(message: String): String {
        val escaped =
            message
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        return "{\"text\":\"$escaped\"}"
    }
}
