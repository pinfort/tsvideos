package me.pinfort.tsvideos.core.external.samba

import jcifs.SmbResource
import org.springframework.core.io.AbstractResource
import java.io.InputStream

/** Built only by [NasComponent.openForRead]; the Samba handle stays an implementation detail. */
class SmbFileResource internal constructor(
    private val smbResource: SmbResource,
    private val length: Long,
) : AbstractResource() {
    override fun getDescription(): String = "SMB resource [${smbResource.name}]"

    // Opening and reading throw storage exceptions, not jcifs ones.
    override fun getInputStream(): InputStream =
        SambaInputStream(sambaOperation("read") { smbResource.openInputStream() }, "read").buffered()

    override fun contentLength(): Long = length

    override fun exists(): Boolean = true
}
