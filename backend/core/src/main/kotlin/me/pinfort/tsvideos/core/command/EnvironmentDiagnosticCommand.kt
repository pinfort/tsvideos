package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.component.DatabaseDiagnosticProbe
import me.pinfort.tsvideos.core.component.NasDiagnosticProbe
import me.pinfort.tsvideos.core.component.diagnosticCancellation
import me.pinfort.tsvideos.core.domain.DiagnosticCheck
import me.pinfort.tsvideos.core.domain.DiagnosticReport
import me.pinfort.tsvideos.core.domain.DiagnosticStatus
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URI

@Component
class EnvironmentDiagnosticCommand(
    private val environment: Environment,
    private val databaseProbe: DatabaseDiagnosticProbe,
    private val nasProbe: NasDiagnosticProbe,
) {
    fun diagnose(checkWrite: Boolean = false): DiagnosticReport {
        diagnosticCancellation()
        val checks = mutableListOf<DiagnosticCheck>()
        val databaseUrl = setting("spring.datasource.url")
        val databaseUser = setting("spring.datasource.username")
        val databasePassword = setting("spring.datasource.password")
        val databaseValid =
            listOf(
                validate(
                    checks,
                    "spring.datasource.url",
                    validDatabaseUrl(databaseUrl),
                    "Set a jdbc:mariadb://host[:port]/database URL without embedded credentials",
                ),
                validate(checks, "spring.datasource.username", !databaseUser.isNullOrBlank(), "Set the database username"),
                validate(checks, "spring.datasource.password", !databasePassword.isNullOrBlank(), "Set the database password"),
            ).all { it }
        checks +=
            if (databaseValid) {
                val success =
                    try {
                        databaseProbe.check(databaseUrl!!, databaseUser!!, databasePassword!!).also { diagnosticCancellation() }
                    } catch (error: Exception) {
                        diagnosticCancellation(error)
                        false
                    }
                DiagnosticCheck(
                    "database.connectivity",
                    if (success) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                    if (success) "Read-only SELECT 1 succeeded" else "Check database availability, credentials and SELECT permission",
                )
            } else {
                DiagnosticCheck("database.connectivity", DiagnosticStatus.UNCHECKED, "Correct database configuration before connecting")
            }
        listOf("video-store-nas", "original-store-nas").forEach { name ->
            val prefix = "samba.$name"
            val id = "nas.$name"
            val url = setting("$prefix.url")
            val user = setting("$prefix.username")
            val password = setting("$prefix.password")
            val baseDir = setting("$prefix.base-dir")?.replace('\\', '/')
            val valid =
                listOf(
                    validate(checks, "$prefix.url", validNasUrl(url), "Set an smb://host[:port]/share URL without embedded credentials"),
                    validate(checks, "$prefix.username", !user.isNullOrBlank(), "Set the NAS username"),
                    validate(checks, "$prefix.password", !password.isNullOrBlank(), "Set the NAS password"),
                    validate(
                        checks,
                        "$prefix.base-dir",
                        validBaseDir(baseDir),
                        "Set a relative NAS base directory, or an empty value for the share root",
                    ),
                ).all { it }
            if (valid) {
                checks += nasProbe.check(id, url!!, user!!, password!!, baseDir!!, checkWrite)
            } else {
                checks += DiagnosticCheck("$id.read", DiagnosticStatus.UNCHECKED, "Correct NAS configuration before connecting")
                checks += DiagnosticCheck("$id.write", DiagnosticStatus.UNCHECKED, "Correct NAS configuration before checking permissions")
            }
        }
        diagnosticCancellation()
        return DiagnosticReport(checks)
    }

    private fun setting(name: String): String? = runCatching { environment.getProperty(name) }.getOrNull()

    private fun validate(
        checks: MutableList<DiagnosticCheck>,
        key: String,
        valid: Boolean,
        remedy: String,
    ): Boolean {
        checks +=
            DiagnosticCheck("config.$key", if (valid) DiagnosticStatus.PASS else DiagnosticStatus.FAIL, if (valid) "Configured" else remedy)
        return valid
    }

    private fun validDatabaseUrl(value: String?): Boolean {
        if (value == null || !value.startsWith("jdbc:mariadb://")) return false
        val credentialKeys = setOf("user", "username", "password")
        val embedsCredentials = value.substringAfter('?', "").split('&').any { it.substringBefore('=').lowercase() in credentialKeys }
        return !embedsCredentials && validUri(value.removePrefix("jdbc:"), "mariadb", allowQuery = true)
    }

    private fun validNasUrl(value: String?): Boolean = value != null && validUri(value, "smb", allowQuery = false)

    private fun validUri(
        value: String,
        scheme: String,
        allowQuery: Boolean,
    ): Boolean =
        runCatching {
            val uri = URI(value)
            uri.scheme == scheme &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null &&
                (uri.port == -1 || uri.port in 1..65535) &&
                !uri.path.trim('/').isBlank() &&
                (allowQuery || uri.query == null) &&
                uri.path.split('/').none { it == ".." }
        }.getOrDefault(false)

    private fun validBaseDir(value: String?): Boolean =
        value != null && !value.startsWith('/') && value.none { it in ":%?#" || it.isISOControl() } && value.split('/').none { it == ".." }
}
