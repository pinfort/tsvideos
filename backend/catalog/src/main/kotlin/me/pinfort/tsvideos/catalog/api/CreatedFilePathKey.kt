package me.pinfort.tsvideos.catalog.api

import java.security.MessageDigest
import java.text.Normalizer
import java.util.HexFormat
import java.util.Locale

/**
 * The alias key of a `created_file` path: paths with the same key may name the same NAS file. SMB accepts both
 * separators, ignores case and drops the trailing dots and spaces of every segment, so the key unifies the separators,
 * trims each segment and folds case (upper then lower case with Java's full Unicode mappings, after NFC composition).
 * It may join paths the NAS keeps apart, which only refuses or serializes more, but must never split paths the NAS
 * joins. Its SHA-256 is stored as `created_file.path_key` and keys `created_file_path_guard` (migration 076).
 */
object CreatedFilePathKey {
    fun of(path: String): String =
        Normalizer
            .normalize(path, Normalizer.Form.NFC)
            .replace('\\', '/')
            .split('/')
            .joinToString("/") { it.trimEnd(' ', '.').uppercase(Locale.ROOT).lowercase(Locale.ROOT) }

    internal fun hash(path: String): ByteArray = sha256(of(path))

    /**
     * The `created_file_path_guard` key of the split [splittedFileId]: claimed by a registration on the split and by a
     * change of the program owning it, so the program's set of files cannot grow while the change runs. The NUL prefix
     * keeps it apart from path keys; a collision would only serialize two writers, never let one through.
     */
    internal fun splitGuard(splittedFileId: Long): ByteArray = sha256("\u0000splitted_file:$splittedFileId")

    /** [keys] without duplicates in ascending order: the one order in which every claimant takes its guards. */
    internal fun claimOrder(keys: Collection<ByteArray>): List<ByteArray> {
        val format = HexFormat.of()
        return keys
            .associateBy { format.formatHex(it) }
            .toSortedMap()
            .values
            .toList()
    }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
}
