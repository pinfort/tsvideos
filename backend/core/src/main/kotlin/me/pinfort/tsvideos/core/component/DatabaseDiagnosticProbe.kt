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
        // Replace caller timeouts: diagnostic connections must not inherit an unbounded wait.
        val query =
            url.substringAfter('?', "").split('&').filter {
                it.isNotBlank() && it.substringBefore('=') !in setOf("connectTimeout", "socketTimeout")
            }
        val boundedUrl = url.substringBefore('?') + "?" + (query + listOf("connectTimeout=3000", "socketTimeout=3000")).joinToString("&")
        val properties =
            Properties().apply {
                setProperty("user", username)
                setProperty("password", password)
            }
        return DriverManager.getConnection(boundedUrl, properties).use { connection ->
            connection.isReadOnly = true
            connection.createStatement().use { statement ->
                statement.queryTimeout = 3
                statement.executeQuery("SELECT 1").use { result -> result.next() && result.getInt(1) == 1 }
            }
        }
    }
}
