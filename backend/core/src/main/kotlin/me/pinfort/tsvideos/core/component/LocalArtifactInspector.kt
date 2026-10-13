package me.pinfort.tsvideos.core.component

import me.pinfort.tsvideos.core.command.ProcessingRecoveryCommand
import me.pinfort.tsvideos.core.domain.InspectionArtifact
import me.pinfort.tsvideos.core.domain.LocalCheckpointFile
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

@Component
class LocalArtifactInspector(
    private val recovery: ProcessingRecoveryCommand,
) {
    fun inspect(
        path: String,
        evidence: LocalCheckpointFile? = null,
        size: Long? = null,
        modified: Long? = null,
    ): InspectionArtifact {
        try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val file = File(path)
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            if (!attributes.isRegularFile) return InspectionArtifact(path, "UNCHECKED", "NONE", "NOT_REGULAR_FILE")
            if ((size != null && attributes.size() != size) || (modified != null && attributes.lastModifiedTime().toMillis() != modified)) {
                return InspectionArtifact(path, "CHANGED", "METADATA", "IDENTITY_CHANGED")
            }
            if (evidence != null && recovery.capture(file, evidence.rowId) != evidence) {
                return InspectionArtifact(path, "CHANGED", "SHA256", "CHECKPOINT_CONTENT_CHANGED")
            }
            return InspectionArtifact(
                path,
                "PRESENT",
                if (evidence !=
                    null
                ) {
                    "SHA256"
                } else if (size != null) {
                    "METADATA"
                } else {
                    "NONE"
                },
                "LOCAL_OBSERVED",
            )
        } catch (failure: Exception) {
            if (ProcessingCancellation.isInterrupted(failure)) throw failure
            return InspectionArtifact(
                path,
                if (failure is NoSuchFileException) "MISSING" else "INACCESSIBLE",
                "NONE",
                if (failure is NoSuchFileException) "ABSENT" else "LOCAL_READ_FAILED",
            )
        }
    }
}
