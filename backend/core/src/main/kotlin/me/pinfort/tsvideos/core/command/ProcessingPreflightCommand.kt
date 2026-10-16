package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.component.EncoderConnectivityProbe
import me.pinfort.tsvideos.core.component.LocalPreflightProbe
import me.pinfort.tsvideos.core.component.diagnosticCancellation
import me.pinfort.tsvideos.core.domain.DiagnosticCheck
import me.pinfort.tsvideos.core.domain.DiagnosticReport
import me.pinfort.tsvideos.core.domain.DiagnosticStatus
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.nio.file.Path

@Component
class ProcessingPreflightCommand(
    private val environment: Environment,
    private val shared: EnvironmentDiagnosticCommand,
    private val local: LocalPreflightProbe,
    private val encoder: EncoderConnectivityProbe,
) {
    fun diagnose(
        paths: List<Path>,
        checkWrite: Boolean = false,
    ): DiagnosticReport {
        diagnosticCancellation()
        val checks = shared.diagnose(checkWrite).checks.toMutableList()
        val inputs = checkInputs(paths, checks)
        checkTools(checks)
        checkEncoder(checks)
        checkSpace(inputs, checks)
        diagnosticCancellation()
        return DiagnosticReport(checks)
    }

    private fun MutableList<DiagnosticCheck>.add(
        id: String,
        status: DiagnosticStatus,
        reason: String,
    ) {
        add(DiagnosticCheck(id, status, reason))
    }

    private fun checkInputs(
        paths: List<Path>,
        checks: MutableList<DiagnosticCheck>,
    ): List<LocalPreflightProbe.Input> {
        val inputs = mutableListOf<LocalPreflightProbe.Input>()
        paths.forEachIndexed { index, path ->
            diagnosticCancellation()
            try {
                val expanded = local.expand(path)
                if (expanded.isEmpty()) {
                    checks.add(
                        "input.$index",
                        DiagnosticStatus.FAIL,
                        "Select a readable recording or directory containing .m2ts files",
                    )
                }
                expanded.forEachIndexed { fileIndex, file ->
                    try {
                        val input = local.inspect(file)
                        require(input.bytes >= 0)
                        inputs += input
                        checks.add(
                            "input.$index.$fileIndex",
                            DiagnosticStatus.PASS,
                            "Readable regular input; canonical path ${input.canonical}; bytes ${input.bytes}",
                        )
                    } catch (error: Exception) {
                        diagnosticCancellation(error)
                        checks.add(
                            "input.$index.$fileIndex",
                            DiagnosticStatus.FAIL,
                            "Check that the selected input is a readable regular file",
                        )
                    }
                }
            } catch (error: Exception) {
                diagnosticCancellation(error)
                checks.add("input.$index", DiagnosticStatus.FAIL, "Check input existence and directory list permission")
            }
        }
        return inputs
    }

    private fun checkTools(checks: MutableList<DiagnosticCheck>) {
        listOf("ts-splitter-path" to true, "amatsukaze-add-task-path" to true, "ffprobe-path" to false).forEach { (name, windowsOnly) ->
            diagnosticCancellation()
            val value = setting("processor-tool.$name")
            val configured = !value.isNullOrBlank() && value.none(Char::isISOControl)
            val found = configured && safe { local.tool(value!!, windowsOnly) }
            val executionChecked = !windowsOnly && !local.windows()
            val passReason =
                if (executionChecked) "Tool file is readable and executable" else "Tool file is readable; execution was not checked"
            checks.add(
                "tool.$name",
                if (found) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                if (found) passReason else "Configure an existing readable tool executable (bare names resolve on PATH)",
            )
            if (windowsOnly && !local.windows()) {
                checks.add(
                    "tool.$name.execution",
                    DiagnosticStatus.UNCHECKED,
                    "This tool is Windows-only; execution is unsupported on this platform and was not tested",
                )
            }
        }
    }

    private fun checkEncoder(checks: MutableList<DiagnosticCheck>) {
        listOf("default-profile", "atx-div-profile").forEach { name ->
            val value = setting("processor-tool.amatsukaze.$name")
            val valid = !value.isNullOrBlank() && value.none(Char::isISOControl)
            checks.add(
                "config.encoder.$name",
                if (valid) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                if (valid) {
                    "Profile name configured; existence on encoder server is not checked"
                } else {
                    "Configure a nonblank encoder profile name without control characters"
                },
            )
        }
        val host = setting("processor-tool.amatsukaze.host")
        val port = setting("processor-tool.amatsukaze.port")?.toIntOrNull()
        val validHost = !host.isNullOrBlank() && safe { isBareHost(host) }
        checks.add(
            "config.encoder.host",
            if (validHost) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
            if (validHost) "Host configured" else "Configure a hostname or IP address without credentials, path or port",
        )
        val validPort = port != null && port in 1..65535
        checks.add(
            "config.encoder.port",
            if (validPort) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
            if (validPort) "Port configured" else "Configure an integer TCP port from 1 through 65535",
        )
        if (validHost && validPort) {
            val connected = safe { encoder.check(host!!, port!!) }
            checks.add(
                "encoder.connectivity",
                if (connected) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                if (connected) {
                    "TCP connected; task acceptance and remote profile existence remain unchecked"
                } else {
                    "Check encoder host, port and firewall; DNS/TCP deadline is 3 seconds"
                },
            )
        } else {
            checks.add("encoder.connectivity", DiagnosticStatus.UNCHECKED, "Correct host and port configuration before connecting")
        }
    }

    private fun isBareHost(host: String): Boolean {
        val uri = URI("tcp://$host")
        return !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.port == -1 &&
            uri.path.isEmpty() &&
            uri.query == null &&
            uri.fragment == null
    }

    private fun checkSpace(
        inputs: List<LocalPreflightProbe.Input>,
        checks: MutableList<DiagnosticCheck>,
    ) {
        // The BigDecimal must also fit a double so the multiplier stays a sane, finite factor.
        val multiplier =
            setting("processor-preflight.space-multiplier", "3")
                ?.toBigDecimalOrNull()
                ?.takeIf { it > BigDecimal.ZERO && it.toDouble().isFinite() }
        val multiplierValid = multiplier != null
        checks.add(
            "config.space-multiplier",
            if (multiplierValid) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
            if (multiplierValid) {
                "Positive finite space estimate multiplier configured"
            } else {
                "Set processor-preflight.space-multiplier to a finite positive number (default 3)"
            },
        )
        inputs.distinctBy { it.canonical }.groupBy { it.storage }.entries.forEachIndexed { index, (storage, members) ->
            val usable = members.mapNotNull { it.usable }.minOrNull()
            if (storage == null || usable == null || multiplier == null) {
                checks.add(
                    "space.$index",
                    DiagnosticStatus.UNCHECKED,
                    "Local usable space or estimate is unavailable; verify capacity before processing",
                )
                return@forEachIndexed
            }
            val totalBytes = members.fold(BigDecimal.ZERO) { sum, input -> sum + input.bytes.toBigDecimal() }
            val required = totalBytes.multiply(multiplier).setScale(0, RoundingMode.CEILING)
            val overflow = required > Long.MAX_VALUE.toBigDecimal()
            if (overflow) {
                checks.add(
                    "space.$index",
                    DiagnosticStatus.FAIL,
                    "Estimated local space exceeds supported byte range; reduce batch size or multiplier",
                )
            } else {
                checks.add(
                    "space.$index",
                    if (usable.toBigDecimal() >= required) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                    "Estimated temporary space: $required bytes for ${members.size} input(s) on this filesystem; usable $usable bytes; multiplier $multiplier (estimate, not a reservation)",
                )
            }
        }
    }

    private fun setting(
        key: String,
        default: String? = null,
    ): String? =
        try {
            environment.getProperty(key) ?: default
        } catch (error: Exception) {
            diagnosticCancellation(error)
            null
        }

    private fun safe(block: () -> Boolean): Boolean =
        try {
            block().also { diagnosticCancellation() }
        } catch (error: Exception) {
            diagnosticCancellation(error)
            false
        }
}
