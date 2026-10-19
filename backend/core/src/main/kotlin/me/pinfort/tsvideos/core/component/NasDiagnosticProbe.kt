package me.pinfort.tsvideos.core.component

import me.pinfort.tsvideos.core.domain.DiagnosticCheck
import me.pinfort.tsvideos.core.domain.DiagnosticStatus
import me.pinfort.tsvideos.core.external.samba.DiagnosticNasClient
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class NasDiagnosticProbe(
    private val client: DiagnosticNasClient,
) {
    fun check(
        id: String,
        url: String,
        username: String,
        password: String,
        baseDir: String,
        checkWrite: Boolean,
    ): List<DiagnosticCheck> {
        diagnosticCancellation()
        val checks = mutableListOf<DiagnosticCheck>()
        try {
            client.open(url, username, password).use { connection ->
                connection.directory(baseDir).use { directory ->
                    inspectDirectory(directory, id, checkWrite, checks)
                }
            }
        } catch (error: Exception) {
            diagnosticCancellation(error)
            if (checks.none { it.id == "$id.read" }) {
                checks +=
                    DiagnosticCheck(
                        "$id.read",
                        DiagnosticStatus.FAIL,
                        "Check NAS availability, credentials, base directory and list permissions",
                    )
            } else {
                checks +=
                    DiagnosticCheck("$id.operation", DiagnosticStatus.FAIL, "A NAS operation failed; check connectivity and permissions")
            }
        }
        if (checks.none { it.id == "$id.write" }) {
            checks +=
                DiagnosticCheck(
                    "$id.write",
                    DiagnosticStatus.UNCHECKED,
                    if (checkWrite) {
                        "Read access must succeed before a write probe"
                    } else {
                        "Write access not tested; use --check-write for an isolated probe"
                    },
                )
        }
        diagnosticCancellation()
        return checks
    }

    private fun inspectDirectory(
        directory: DiagnosticNasClient.Directory,
        id: String,
        checkWrite: Boolean,
        checks: MutableList<DiagnosticCheck>,
    ) {
        if (!directory.isDirectory()) {
            checks += DiagnosticCheck("$id.read", DiagnosticStatus.FAIL, "Create or correct the configured NAS base directory")
            return
        }
        // Opening the listing (and fetching a first entry) checks directory listing without reading recordings.
        directory.listFirstEntry()
        checks += DiagnosticCheck("$id.read", DiagnosticStatus.PASS, "NAS base directory exists and can be listed")
        if (checkWrite) writeProbe(directory, id, checks)
    }

    private fun writeProbe(
        directory: DiagnosticNasClient.Directory,
        id: String,
        checks: MutableList<DiagnosticCheck>,
    ) {
        val name = ".tsvideos-doctor-${UUID.randomUUID()}.tmp"
        var created = false
        directory.file(name).use { probe ->
            try {
                // Exclusive creation is what proves the probe file is ours to delete.
                probe.createExclusive().use {
                    created = true
                    it.write(PROBE_CONTENT)
                }
                probe.openInputStream().use { input ->
                    check(input.readNBytes(PROBE_CONTENT.size + 1).contentEquals(PROBE_CONTENT))
                }
                checks += DiagnosticCheck("$id.write", DiagnosticStatus.PASS, "Isolated temporary file could be written and read")
            } catch (error: Exception) {
                diagnosticCancellation(error)
                checks += DiagnosticCheck("$id.write", DiagnosticStatus.FAIL, "Check NAS create, write and read permissions")
            } finally {
                if (created) {
                    try {
                        probe.delete()
                        checks += DiagnosticCheck("$id.cleanup", DiagnosticStatus.PASS, "Temporary probe removed")
                    } catch (error: Exception) {
                        diagnosticCancellation(error)
                        checks +=
                            DiagnosticCheck(
                                "$id.cleanup",
                                DiagnosticStatus.FAIL,
                                "Remove diagnostic probe $name from the configured NAS base directory",
                            )
                    }
                } else {
                    checks +=
                        DiagnosticCheck(
                            "$id.cleanup",
                            DiagnosticStatus.UNCHECKED,
                            "Creation did not confirm ownership; inspect diagnostic probe $name in the NAS base directory if needed",
                        )
                }
            }
        }
    }

    companion object {
        private val PROBE_CONTENT = "tsvideos-doctor\n".toByteArray(Charsets.UTF_8)
    }
}
