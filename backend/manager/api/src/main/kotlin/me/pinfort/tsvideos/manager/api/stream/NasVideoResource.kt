package me.pinfort.tsvideos.manager.api.stream

import me.pinfort.tsvideos.core.storage.NasReadHandle
import org.springframework.core.io.AbstractResource
import java.io.InputStream

/**
 * Adapts a storage read handle to the `Resource` that `ResourceRegionHttpMessageConverter` writes. Each
 * [getInputStream] opens a new stream, which the converter closes; the handle itself is owned and closed by
 * [ResponseResources] when the request finishes, never by this resource.
 */
class NasVideoResource(
    private val handle: NasReadHandle,
) : AbstractResource() {
    override fun getDescription(): String = "NAS video"

    override fun getInputStream(): InputStream = handle.open()

    override fun contentLength(): Long = handle.length

    override fun exists(): Boolean = true
}
