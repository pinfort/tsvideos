package me.pinfort.tsvideos.core.domain

import java.io.InputStream
import java.security.MessageDigest

fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

/** Streams the remaining input; the caller owns closing it. */
fun InputStream.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    // Large reads amortize SMB round trips for multi-GB recordings.
    val buffer = ByteArray(1 shl 20)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.hex()
}
