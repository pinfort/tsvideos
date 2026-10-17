package me.pinfort.tsvideos.core.external.samba

import jcifs.SmbResource
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import org.springframework.stereotype.Component
import java.util.Properties

@Component
class DiagnosticNasClient {
    class Connection(
        val root: SmbResource,
        private val closeContext: () -> Unit,
    ) : AutoCloseable {
        override fun close() {
            try {
                root.close()
            } finally {
                closeContext()
            }
        }
    }

    fun open(
        url: String,
        username: String,
        password: String,
    ): Connection {
        val properties =
            Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                setProperty("jcifs.smb.client.connTimeout", "3000")
                setProperty("jcifs.smb.client.responseTimeout", "3000")
                setProperty("jcifs.smb.client.soTimeout", "3000")
                setProperty("jcifs.smb.client.maxRequestRetries", "1")
            }
        val base = BaseContext(PropertyConfiguration(properties))
        val authenticated = base.withCredentials(NtlmPasswordAuthenticator(username, password))
        try {
            val root = SmbFile(if (url.endsWith('/')) url else "$url/", authenticated)
            return Connection(root) {
                authenticated.close()
                base.close()
            }
        } catch (failure: Exception) {
            authenticated.close()
            base.close()
            throw failure
        }
    }
}
