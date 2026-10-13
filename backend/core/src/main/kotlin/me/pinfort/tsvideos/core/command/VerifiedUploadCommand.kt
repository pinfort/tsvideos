package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.UploadManifest
import me.pinfort.tsvideos.core.domain.VerifiedUploadReceipt
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.UploadManifestMapper
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.core.external.samba.VerifiedUploadNas
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VerifiedUploadCommand(
    private val manifests: UploadManifestMapper,
    private val nas: VerifiedUploadNas,
    private val createdFiles: CreatedFileCommand,
    private val createdMapper: CreatedFileMapper,
    transactionManager: PlatformTransactionManager,
    @Value("\${processor.upload.verify-sha256:false}") private val checksumRequired: Boolean = false,
) {
    private val transaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    fun upload(
        jobId: Long,
        artifact: String,
        source: File,
        type: SambaClient.NasType,
        target: String,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): VerifiedUploadReceipt {
        require(jobId > 0 && artifact.isNotBlank())
        require(
            target.isNotBlank() &&
                !target.startsWith('/') &&
                '\\' !in target &&
                target.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                ':' !in target &&
                '?' !in target &&
                '#' !in target &&
                '%' !in target &&
                target.none { it.isISOControl() },
        )
        val key = ProcessingIdentity.hash(artifact)
        var manifest = manifests.find(jobId, key)
        if (manifest == null) {
            val identity = ProcessingIdentity.from(source)
            val digest = digest(source)
            check(ProcessingIdentity.from(source) == identity) { "Source changed while fingerprinting" }
            val proposed =
                UploadManifest(
                    0,
                    jobId,
                    key,
                    ProcessingIdentity.hash("${type.name}\u0000$target"),
                    type.name,
                    target,
                    "$target.upload-${UUID.randomUUID()}",
                    identity.path,
                    identity.size,
                    identity.modifiedMillis,
                    digest,
                    checksumRequired,
                    "NEW",
                    null,
                    null,
                )
            try {
                transaction.execute { manifests.insert(proposed) }
            } catch (
                _: DuplicateKeyException,
            ) {
                // Read winning ownership below.
            }
            manifest = manifests.find(jobId, key)
                ?: error("Destination belongs to another upload; reconciliation required")
        }
        check(manifest.nasType == type.name && manifest.finalPath == target && manifest.sourcePath == source.canonicalPath) {
            "Upload identity changed; reconciliation required"
        }
        val id = manifest.id
        withClaim(id) { initial, token ->
            var current = initial

            fun transition(next: String) {
                check(manifests.transition(id, token, current.state, next) == 1) { "Upload state changed" }
                current = current.copy(state = next)
            }
            if (current.state in setOf("FINALIZED", "REGISTERED")) {
                verifyRemote(current, current.finalPath)
                if (source.exists()) verifySource(current, source)
                return@withClaim
            }
            verifySource(current, source)
            check(current.state != "FINALIZING") { "Ambiguous rename outcome; manual reconciliation required" }
            if (current.state == "CREATING") error("Ambiguous temporary creation; manual reconciliation required")
            if (current.state == "TRANSFERRING") {
                // Only this state proves exclusive open succeeded. Never delete a merely reserved path.
                nas.delete(type, current.temporaryPath)
                transition("NEW")
            }
            if (current.state == "NEW") {
                check(!nas.exists(type, target)) { "Unowned final destination exists" }
                transition("CREATING")
                nas.transfer(type, current.temporaryPath, source, { transition("TRANSFERRING") }, progress)
                verifySource(current, source)
                verifyRemote(current, current.temporaryPath)
                transition("TEMP_VERIFIED")
            }
            check(current.state == "TEMP_VERIFIED") { "Upload requires reconciliation" }
            verifyRemote(current, current.temporaryPath)
            check(!nas.exists(type, target)) { "Unowned final destination exists" }
            transition("FINALIZING")
            nas.finalize(type, current.temporaryPath, target)
            verifyRemote(current, target)
            transition("FINALIZED")
        }
        return VerifiedUploadReceipt(id)
    }

    fun register(
        receipt: VerifiedUploadReceipt,
        splittedFileId: Long,
        mime: String?,
        encoding: String?,
    ): CreatedFile =
        withClaim(receipt.manifestId) { manifest, token ->
            check(manifest.state in setOf("FINALIZED", "REGISTERED")) { "Upload is not verified" }
            verifyRemote(manifest, manifest.finalPath)
            if (manifest.state == "REGISTERED") {
                val file =
                    createdMapper.find(checkNotNull(manifest.createdFileId))?.toDomain()
                        ?: error("Registered artifact record missing")
                check(
                    file.splittedFileId == splittedFileId &&
                        file.file == manifest.finalPath &&
                        file.size == manifest.sourceSize &&
                        file.mime == mime &&
                        file.encoding == encoding &&
                        file.status == CreatedFile.Status.FILE_MOVED,
                )
                file
            } else {
                checkNotNull(
                    transaction.execute {
                        val file = createdFiles.insert(splittedFileId, manifest.finalPath, manifest.sourceSize, mime, encoding)
                        check(manifests.register(manifest.id, token, file.id) == 1) { "Upload registration lost ownership" }
                        file
                    },
                )
            }
        }

    fun deleteLocal(receipt: VerifiedUploadReceipt) {
        withClaim(receipt.manifestId) { manifest, _ ->
            requireRegistered(manifest)
            val source = File(manifest.sourcePath)
            if (source.exists()) {
                verifySource(manifest, source)
                Files.delete(source.toPath())
            }
        }
    }

    /** Used before deleting the raw recording: no metadata-only legacy receipt is accepted. */
    fun verifyRegistered(
        jobId: Long,
        artifact: String,
    ): VerifiedUploadReceipt {
        val manifest = manifests.find(jobId, ProcessingIdentity.hash(artifact)) ?: error("Verified archive receipt missing")
        withClaim(manifest.id) { current, _ -> requireRegistered(current) }
        return VerifiedUploadReceipt(manifest.id)
    }

    private fun requireRegistered(manifest: UploadManifest) {
        check(manifest.state == "REGISTERED") { "Remote artifact has not been registered" }
        val file =
            createdMapper.find(checkNotNull(manifest.createdFileId))?.toDomain()
                ?: error("Registered artifact record missing")
        check(file.file == manifest.finalPath && file.size == manifest.sourceSize && file.status == CreatedFile.Status.FILE_MOVED)
        verifyRemote(manifest, manifest.finalPath)
    }

    /** Call after the processing job has entered RESETTING, before destructive program removal. */
    fun validateReset(jobId: Long) {
        manifests.forJob(jobId).forEach { manifest ->
            check(manifest.claimToken == null && manifest.state !in setOf("CREATING", "FINALIZING")) {
                "Active or ambiguous upload must be reconciled before reset"
            }
            if (nas.exists(SambaClient.NasType.valueOf(manifest.nasType), manifest.finalPath)) {
                check(manifest.state == "REGISTERED") { "Unregistered final upload must be reconciled before reset" }
                requireRegistered(manifest)
            }
        }
    }

    /** Explicit cleanup of one durable owned temporary artifact; never scans the share. */
    fun cleanupTemporary(
        jobId: Long,
        artifact: String,
    ) {
        val manifest = manifests.find(jobId, ProcessingIdentity.hash(artifact)) ?: return
        withClaim(manifest.id) { current, token ->
            check(current.state in setOf("NEW", "TRANSFERRING", "TEMP_VERIFIED")) { "Upload cannot safely clean temporary path" }
            if (current.state != "NEW") {
                nas.delete(SambaClient.NasType.valueOf(current.nasType), current.temporaryPath)
                check(manifests.transition(current.id, token, current.state, "NEW") == 1)
            }
        }
    }

    /** Explicit reset only: no glob/age sweep and no unproven temporary ownership. */
    fun retireAfterReset(jobId: Long) {
        manifests.forJob(jobId).forEach { entry ->
            withClaim(entry.id) { current, token ->
                check(current.state !in setOf("CREATING", "FINALIZING")) { "Ambiguous upload must be reconciled before reset" }
                check(current.createdFileId == null || createdMapper.find(current.createdFileId) == null) {
                    "Registered file row remains; preserve upload ownership"
                }
                val type = SambaClient.NasType.valueOf(current.nasType)
                check(!nas.exists(type, current.finalPath)) { "Final artifact remains; preserve upload ownership" }
                if (current.state in setOf("TRANSFERRING", "TEMP_VERIFIED")) nas.delete(type, current.temporaryPath)
                check(manifests.remove(current.id, token) == 1)
            }
        }
    }

    private fun verifyRemote(
        manifest: UploadManifest,
        path: String,
    ) {
        val type = SambaClient.NasType.valueOf(manifest.nasType)
        check(nas.size(type, path) == manifest.sourceSize) { "Remote size mismatch; local source preserved" }
        if (manifest.checksumRequired) {
            check(nas.sha256(type, path) == manifest.sourceSha256) { "Remote SHA-256 mismatch" }
        }
    }

    private fun verifySource(
        manifest: UploadManifest,
        source: File,
    ) {
        val identity = ProcessingIdentity.from(source)
        check(
            identity.path == manifest.sourcePath &&
                identity.size == manifest.sourceSize &&
                identity.modifiedMillis == manifest.sourceModified &&
                digest(source) == manifest.sourceSha256 &&
                ProcessingIdentity.from(source) == identity,
        ) { "Local source changed; preserved" }
    }

    private fun digest(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    private fun <T> withClaim(
        id: Long,
        action: (UploadManifest, String) -> T,
    ): T {
        val token = UUID.randomUUID().toString()
        check(manifests.claim(id, token) == 1) { "Upload already claimed; interrupted owner requires manual reconciliation" }
        try {
            return action(checkNotNull(manifests.get(id)), token)
        } finally {
            manifests.release(id, token)
        }
    }
}
