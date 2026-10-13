package me.pinfort.tsvideos.core.external.samba

import me.pinfort.tsvideos.core.domain.VerificationStatus
import org.springframework.stereotype.Component

/** Read-only metadata checks, in the same store order as playback/cleanup resolution. */
@Component
class NasFileVerifier(
    private val sambaClient: SambaClient,
) {
    data class Result(
        val status: VerificationStatus,
        val actualBytes: Long? = null,
        val reason: String? = null,
    )

    fun verify(
        file: String,
        expectedBytes: Long,
    ): Result {
        val normalized = file.replace('\\', '/')
        if (expectedBytes < 0 ||
            normalized.isBlank() ||
            normalized.startsWith('/') ||
            ':' in normalized ||
            normalized.split('/').any { it == ".." }
        ) {
            return Result(VerificationStatus.UNCHECKED, reason = "Invalid NAS path or recorded size")
        }
        // If a higher-priority store cannot be checked, do not claim a fallback is the resolved file.
        return try {
            for (store in listOf(sambaClient::videoStoreNas, sambaClient::originalStoreNas)) {
                store().use { root ->
                    root.resolve(normalized).use { resource ->
                        if (resource.exists()) {
                            if (!resource.isFile) return Result(VerificationStatus.UNCHECKED, reason = "Path is not a regular file")
                            val size = resource.length()
                            return Result(
                                if (size == expectedBytes) VerificationStatus.VERIFIED else VerificationStatus.SIZE_MISMATCH,
                                size,
                            )
                        }
                    }
                }
            }
            Result(VerificationStatus.MISSING, reason = "Absent from both NAS stores")
        } catch (_: Exception) {
            // Exception messages can contain server URLs and credentials.
            Result(VerificationStatus.INACCESSIBLE, reason = "NAS metadata could not be read")
        }
    }
}
