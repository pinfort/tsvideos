package me.pinfort.tsvideos.core.component

import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.core.storage.NasStore
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class NasDestinationResolver(
    private val normalizeComponent: NormalizeComponent,
    private val sambaClient: SambaClient,
) {
    fun resolve(
        recordingDirectory: Path,
        fileName: String,
        nasType: NasStore,
    ): String {
        val directory = normalizeComponent.normalize(recordingDirectory.fileName.toString())
        return sambaClient.resolvePathUnderBaseDir(nasType, "${directory.take(1)}/$directory/$fileName")
    }
}
