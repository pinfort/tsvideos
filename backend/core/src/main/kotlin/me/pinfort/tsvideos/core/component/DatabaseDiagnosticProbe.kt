package me.pinfort.tsvideos.core.component

import org.springframework.stereotype.Component
import java.sql.DriverManager
import java.util.Properties

@Component
class DatabaseDiagnosticProbe {
    /** A separate, short-lived connection: no pool initialization, schema changes or library writes. */
    fun check(
        url: String,
        username: String,
        password: String,
    ): Boolean {
        // Replace caller timeouts in the URL itself: URL options take precedence over Properties in Connector/J,
        // so a URL connectTimeout=0 would otherwise reintroduce an unbounded wait.
        val query =
            url.substringAfter('?', "").split('&').filter {
                it.isNotBlank() && it.substringBefore('=').lowercase() !in setOf("connecttimeout", "sockettimeout")
            }
        val boundedUrl =
            url.substringBefore('?') + "?" +
                (query + listOf("connectTimeout=$TIMEOUT_MILLIS", "socketTimeout=$TIMEOUT_MILLIS")).joinToString("&")
        val properties =
            Properties().apply {
                setProperty("user", username)
                setProperty("password", password)
            }
        return DriverManager.getConnection(boundedUrl, properties).use { connection ->
            connection.isReadOnly = true
            connection.createStatement().use { statement ->
                statement.queryTimeout = TIMEOUT_MILLIS / 1000
                statement.executeQuery("SELECT 1").use { result -> result.next() && result.getInt(1) == 1 }
            }
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 3000
    }
}
