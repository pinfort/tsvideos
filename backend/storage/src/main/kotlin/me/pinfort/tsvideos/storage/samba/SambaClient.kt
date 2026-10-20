package me.pinfort.tsvideos.storage.samba

import jcifs.CIFSContext
import jcifs.SmbResource
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import me.pinfort.tsvideos.storage.api.NasPaths
import me.pinfort.tsvideos.storage.api.NasStore
import org.springframework.stereotype.Component
import java.util.Properties

/** Samba (jcifs) connection to the NAS stores. Share roots never leave the storage module. */
@Component
internal class SambaClient(
    private val sambaConfigurationProperties: SambaConfigurationProperties,
) : NasPaths {
    /** A new share root for [store]; the caller owns and closes it. */
    fun root(store: NasStore): SmbResource =
        when (store) {
            NasStore.VIDEO_STORE_NAS -> videoStoreNas()
            NasStore.ORIGINAL_STORE_NAS -> originalStoreNas()
        }

    fun videoStoreNas(): SmbResource {
        val context =
            cifsContext(
                sambaConfigurationProperties.videoStoreNas.username,
                sambaConfigurationProperties.videoStoreNas.password,
            )
        return connect(sambaConfigurationProperties.videoStoreNas.url, context)
    }

    fun originalStoreNas(): SmbResource {
        val context =
            cifsContext(
                sambaConfigurationProperties.originalStoreNas.username,
                sambaConfigurationProperties.originalStoreNas.password,
            )
        return connect(sambaConfigurationProperties.originalStoreNas.url, context)
    }

    /**
     * videoStoreNas()/originalStoreNas() は共有ルートを返すだけで baseDir を解決しないため、
     * DB に永続化するパス (CreatedFile.file など) はこれで baseDir を織り込んだ、共有ルートからの
     * 相対パスとして組み立てる。videoStoreNas()/originalStoreNas() で resolve するどのパスも
     * baseDir を含んでいる前提になる。
     */
    override fun resolvePathUnderBaseDir(
        nasType: NasStore,
        relativePath: String,
    ): String {
        val baseDir =
            when (nasType) {
                NasStore.VIDEO_STORE_NAS -> sambaConfigurationProperties.videoStoreNas.baseDir
                NasStore.ORIGINAL_STORE_NAS -> sambaConfigurationProperties.originalStoreNas.baseDir
            }
        return if (baseDir.isBlank()) relativePath else "${baseDir.trim('/')}/$relativePath"
    }

    private fun connect(
        url: String,
        context: CIFSContext,
    ): SmbFile = SmbFile(if (url.endsWith("/")) url else "$url/", context)

    private fun cifsContext(
        username: String,
        password: String,
    ): CIFSContext {
        val auth =
            NtlmPasswordAuthenticator(
                username,
                password,
            )
        return baseContext.withCredentials(auth)
    }

    private val properties =
        Properties()
            .apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
            }

    private val baseContext =
        BaseContext(
            PropertyConfiguration(properties),
        )
}
