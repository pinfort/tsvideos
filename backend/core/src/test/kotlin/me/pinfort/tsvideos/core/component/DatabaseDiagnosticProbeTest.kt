package me.pinfort.tsvideos.core.component

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mariadb.MariaDBContainer
import java.sql.DriverManager
import java.sql.SQLException

class DatabaseDiagnosticProbeTest :
    ExpectSpec({
        val database =
            MariaDBContainer("mariadb:11.8")
                .withDatabaseName("doctor")
                .withUsername("doctor")
                .withPassword("test-password")
        beforeSpec { database.start() }
        afterSpec { database.stop() }

        expect("SELECT 1 checks real MariaDB access without altering library records") {
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS library (id INT PRIMARY KEY)")
                    statement.executeUpdate("INSERT IGNORE INTO library VALUES (1)")
                }
            }
            DatabaseDiagnosticProbe().check(database.jdbcUrl, database.username, database.password) shouldBe true
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM library").use { result ->
                        result.next() shouldBe true
                        result.getInt(1) shouldBe 1
                    }
                }
            }
        }
        expect("invalid database credentials fail instead of claiming connectivity") {
            shouldThrow<SQLException> { DatabaseDiagnosticProbe().check(database.jdbcUrl, database.username, "incorrect") }
        }
    })
