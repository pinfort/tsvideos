package me.pinfort.tsvideos.console.common.charset

import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

/**
 * Decides the charset of human-readable log output (stderr) for both consoles.
 *
 * Machine output (`--json` on stdout) is always UTF-8 and is configured separately by each console.
 * Logs, in contrast, are read by people on a terminal, so they follow the terminal: since JDK 19
 * `System.err.charset()` is the console code page (e.g. MS932 on a Japanese Windows terminal) when
 * stderr is a terminal and `native.encoding` when it is redirected.
 * Set [ENV_NAME] (e.g. `UTF-8`) to force a charset, mainly for redirected logs.
 */
object ConsoleCharset {
    const val ENV_NAME = "TSVIDEOS_LOG_CHARSET"

    /** System property read by `logback-spring.xml` as `tsvideos.log.charset`. */
    const val PROPERTY_NAME = "tsvideos.log.charset"

    fun resolveLogCharset(
        override: String? = System.getenv(ENV_NAME),
        stderrCharset: Charset = System.err.charset(),
    ): Charset {
        val name = override?.trim().orEmpty()
        if (name.isEmpty()) return stderrCharset
        return try {
            Charset.forName(name)
        } catch (_: IllegalCharsetNameException) {
            stderrCharset
        } catch (_: UnsupportedCharsetException) {
            stderrCharset
        }
    }

    /** Call first in `main`, before Spring initializes logback. Respects an explicit `-Dtsvideos.log.charset`. */
    fun configureLogging() {
        if (System.getProperty(PROPERTY_NAME).isNullOrBlank()) {
            System.setProperty(PROPERTY_NAME, resolveLogCharset().name())
        }
    }
}
