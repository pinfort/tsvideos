package me.pinfort.tsvideos.core.component

import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Component
class LocalPreflightProbe {
    data class Input(
        val canonical: Path,
        val bytes: Long,
        val storage: Any?,
        val usable: Long?,
    )

    fun expand(path: Path): List<Path> = expandRecordingInputs(path)

    fun inspect(path: Path): Input {
        require(Files.isRegularFile(path) && Files.isReadable(path))
        val canonical = path.toRealPath()
        val store =
            try {
                Files.getFileStore(canonical.parent)
            } catch (error: Exception) {
                diagnosticCancellation(error)
                null
            }
        val usable =
            try {
                store?.usableSpace?.takeIf { it >= 0 }
            } catch (error: Exception) {
                diagnosticCancellation(error)
                null
            }
        return Input(canonical, Files.size(canonical), store, usable)
    }

    fun tool(
        value: String,
        windowsOnly: Boolean,
    ): Boolean {
        val direct = Path.of(value)
        val windows = windows()
        val candidates =
            if (direct.isAbsolute || value.contains('/') || value.contains('\\')) {
                listOf(direct)
            } else {
                val extensions = if (windows) listOf("", ".exe", ".com", ".bat", ".cmd") else listOf("")
                System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }.flatMap { dir ->
                    extensions.map { Path.of(dir, value + it) }
                }
            }
        // Windows has no executable bit, and Windows-only tools cannot run here anyway (reported UNCHECKED).
        val executionCheckable = !windows && !windowsOnly
        return candidates.any { Files.isRegularFile(it) && Files.isReadable(it) && (!executionCheckable || Files.isExecutable(it)) }
    }

    fun windows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}

@Component
class EncoderConnectivityProbe {
    /** Entire DNS + TCP attempt has a caller deadline; daemon DNS worker cannot prevent process exit. */
    fun check(
        host: String,
        port: Int,
    ): Boolean {
        diagnosticCancellation()
        val executor = Executors.newSingleThreadExecutor { Thread(it, "encoder-preflight").apply { isDaemon = true } }
        val socket = Socket()
        val future =
            executor.submit<Boolean> {
                socket.connect(InetSocketAddress(host, port), 3000)
                true
            }
        return try {
            future.get(3000, TimeUnit.MILLISECONDS).also { diagnosticCancellation() }
        } catch (error: Exception) {
            diagnosticCancellation(error)
            false
        } finally {
            socket.close()
            future.cancel(true)
            executor.shutdownNow()
        }
    }
}
