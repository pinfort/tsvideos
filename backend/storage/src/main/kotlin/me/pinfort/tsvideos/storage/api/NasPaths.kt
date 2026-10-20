package me.pinfort.tsvideos.storage.api

/** Builds the share-relative paths that are persisted for NAS artifacts. */
interface NasPaths {
    /**
     * [relativePath] under the configured base directory of [nasType], relative to the share root. Every path the
     * storage operations resolve (and every path persisted in the DB, such as `created_file.file`) includes the base
     * directory; a blank base directory leaves [relativePath] unchanged.
     */
    fun resolvePathUnderBaseDir(
        nasType: NasStore,
        relativePath: String,
    ): String
}
