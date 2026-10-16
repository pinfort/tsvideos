package me.pinfort.tsvideos.core.component

import jcifs.SmbConstants
import jcifs.SmbResource
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
        val checks = mutableListOf<DiagnosticCheck>()
        try {
            client.open(url, username, password).use { connection ->
                if (baseDir.isBlank()) {
                    inspectDirectory(connection.root, id, checkWrite, checks)
                } else {
                    connection.root.resolve("${baseDir.trim('/')}/").use { directory ->
                        inspectDirectory(directory, id, checkWrite, checks)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
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
        return checks
    }

    private fun inspectDirectory(
        directory: SmbResource,
        id: String,
        checkWrite: Boolean,
        checks: MutableList<DiagnosticCheck>,
    ) {
        if (!directory.exists() || !directory.isDirectory) {
            checks += DiagnosticCheck("$id.read", DiagnosticStatus.FAIL, "Create or correct the configured NAS base directory")
            return
        }
        // Opening the iterator (and fetching a first entry) checks directory listing without reading recordings.
        directory.children().use { children ->
            if (children.hasNext()) children.next().close()
        }
        checks += DiagnosticCheck("$id.read", DiagnosticStatus.PASS, "NAS base directory exists and can be listed")
        if (checkWrite) writeProbe(directory, id, checks)
    }

    private fun writeProbe(
        directory: SmbResource,
        id: String,
        checks: MutableList<DiagnosticCheck>,
    ) {
        val name = ".tsvideos-doctor-${UUID.randomUUID()}.tmp"
        var created = false
        directory.resolve(name).use { probe ->
            try {
                // jcifs createNewFile uses OPEN_IF on SMB2. Explicit O_EXCL is required for ownership.
                probe
                    .openOutputStream(
                        true,
                        SmbConstants.O_WRONLY or SmbConstants.O_CREAT or SmbConstants.O_EXCL,
                        SmbConstants.FILE_WRITE_DATA,
                        SmbConstants.FILE_NO_SHARE,
                    ).use {
                        created = true
                        it.write(PROBE_CONTENT)
                    }
                probe.openInputStream().use { input ->
                    check(input.readNBytes(PROBE_CONTENT.size + 1).contentEquals(PROBE_CONTENT))
                }
                checks += DiagnosticCheck("$id.write", DiagnosticStatus.PASS, "Isolated temporary file could be written and read")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (_: Exception) {
                checks += DiagnosticCheck("$id.write", DiagnosticStatus.FAIL, "Check NAS create, write and read permissions")
            } finally {
                if (created) {
                    try {
                        probe.delete()
                        checks += DiagnosticCheck("$id.cleanup", DiagnosticStatus.PASS, "Temporary probe removed")
                    } catch (_: Exception) {
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
