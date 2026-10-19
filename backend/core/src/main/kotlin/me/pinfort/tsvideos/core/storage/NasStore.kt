package me.pinfort.tsvideos.core.storage

/**
 * The NAS share an artifact lives on. Public storage value type, independent of the Samba implementation.
 *
 * Constant names are persisted verbatim (`upload_manifest.nas_type`), so renaming one requires a data migration.
 * Formerly `SambaClient.NasType`; the constant names and order are unchanged.
 */
enum class NasStore {
    VIDEO_STORE_NAS,
    ORIGINAL_STORE_NAS,
}
