package me.pinfort.tsvideos.diagnostics.api

import org.springframework.stereotype.Component
import java.sql.DriverManager
import java.util.Properties

@Component
class DatabaseDiagnosticProbe {
    /**
     * A separate, short-lived connection: no pool initialization, schema changes or library writes.
     *
     * @return whether `SELECT 1` returned 1
     * @throws java.sql.SQLException when connecting, authenticating or querying fails
     */
    fun check(
        url: String,
        username: String,
        password: String,
    ): Boolean {
        // Replace caller timeouts in the URL itself: URL options take precedence over Properties in Connector/J,
        // so a URL connectTimeout=0 would otherwise reintroduce an unbounded wait. Validated URLs use only
        // standard `?key=value&...` options, so splitting on '&' is sufficient.
        val query =
            url.substringAfter('?', "").split('&').filter {
                it.isNotBlank() && it.substringBefore('=').lowercase() !in setOf("connecttimeout", "sockettimeout")
            }
        val boundedUrl =
            url.substringBefore('?') + "?" +
                (query + listOf("connectTimeout=${TIMEOUT_SECONDS * 1000}", "socketTimeout=${TIMEOUT_SECONDS * 1000}")).joinToString("&")
        val properties =
            Properties().apply {
                setProperty("user", username)
                setProperty("password", password)
            }
        return DriverManager.getConnection(boundedUrl, properties).use { connection ->
            connection.isReadOnly = true
            connection.createStatement().use { statement ->
                statement.queryTimeout = TIMEOUT_SECONDS
                statement.executeQuery("SELECT 1").use { result -> result.next() && result.getInt(1) == 1 }
            }
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 3
    }
}
