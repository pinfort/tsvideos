package me.pinfort.tsvideos.storage.api

/**
 * Read-only metadata checks of recorded paths, in the same store order as playback/cleanup resolution
 * (VIDEO_STORE_NAS, then ORIGINAL_STORE_NAS). Checks never throw NAS failures; they become [Status.INACCESSIBLE].
 *
 * The result is a low-level storage status. What it means for a program (persisted health findings, API/CLI output,
 * exit codes) is decided by the caller, which maps it to its own status type.
 */
interface NasFileVerifier {
    enum class Status {
        /** The file exists as a regular file with the expected size. */
        VERIFIED,

        /** The file is absent from both stores. */
        MISSING,

        /** The file exists with a different size. */
        SIZE_MISMATCH,

        /** A store or the file metadata could not be read. */
        INACCESSIBLE,

        /** The path or size cannot be checked (invalid path, negative size, not a regular file). */
        UNCHECKED,
    }

    data class Result(
        val status: Status,
        val actualBytes: Long? = null,
        val reason: String? = null,
    )

    /** Checks made through one session share store roots: each root is opened at most once and closed with the session. */
    interface Session : AutoCloseable {
        fun verify(
            file: String,
            expectedBytes: Long,
        ): Result

        override fun close()
    }

    /** Opens a session for all checks made inside [block] and closes it afterwards. */
    fun <T> session(block: (Session) -> T): T

    /** One-shot check that opens its own session; use [session] when checking many files. */
    fun verify(
        file: String,
        expectedBytes: Long,
    ): Result = session { it.verify(file, expectedBytes) }
}
