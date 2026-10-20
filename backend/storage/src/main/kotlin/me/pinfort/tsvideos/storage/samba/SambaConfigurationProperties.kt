package me.pinfort.tsvideos.storage.samba

import org.springframework.boot.context.properties.ConfigurationProperties

/** The `samba.*` settings (defaults in application-storage.yaml); registered by [SambaStorageConfiguration]. */
@ConfigurationProperties(prefix = "samba")
internal data class SambaConfigurationProperties(
    val videoStoreNas: Server,
    val originalStoreNas: Server,
) {
    data class Server(
        val url: String,
        val username: String,
        val password: String,
        val baseDir: String,
    )
}
