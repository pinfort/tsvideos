package me.pinfort.tsvideos.processing.api

import me.pinfort.tsvideos.catalog.api.NormalizeComponent
import me.pinfort.tsvideos.storage.api.NasPaths
import me.pinfort.tsvideos.storage.api.NasStore
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class NasDestinationResolver(
    private val normalizeComponent: NormalizeComponent,
    private val nasPaths: NasPaths,
) {
    fun resolve(
        recordingDirectory: Path,
        fileName: String,
        nasType: NasStore,
    ): String {
        val directory = normalizeComponent.normalize(recordingDirectory.fileName.toString())
        return nasPaths.resolvePathUnderBaseDir(nasType, "${directory.take(1)}/$directory/$fileName")
    }
}
