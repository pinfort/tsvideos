package me.pinfort.tsvideos.storage.samba

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Binds the NAS settings for the Samba components, so applications only component-scan `me.pinfort.tsvideos.storage`
 * and never need to know the internal properties class.
 */
@Configuration
@EnableConfigurationProperties(SambaConfigurationProperties::class)
internal class SambaStorageConfiguration
