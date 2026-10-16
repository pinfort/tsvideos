package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.UploadManifest
import me.pinfort.tsvideos.core.domain.UploadManifest.State
import me.pinfort.tsvideos.core.domain.VerifiedUploadReceipt
import me.pinfort.tsvideos.core.domain.sha256Hex
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

    /** A manifest claimed by this invocation; every transition is compare-and-set on the claim token. */
    private inner class Claimed(
        var manifest: UploadManifest,
        private val token: String,
    ) {
        fun transition(next: State) {
            check(transaction.execute { manifests.transition(manifest.id, token, manifest.state, next) } == 1) { "Upload state changed" }
            manifest = manifest.copy(state = next)
        }
    }

    fun upload(
        jobId: Long,
        artifact: String,
        source: File,
        type: SambaClient.NasType,
        target: String,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): VerifiedUploadReceipt {
        require(jobId > 0) { "Upload job id must be positive: $jobId" }
        require(artifact.isNotBlank()) { "Upload artifact name must not be blank" }
        validateTarget(target)
        val (manifest, freshDigest) = findOrCreateManifest(jobId, ProcessingIdentity.hash(artifact), source, type, target)
        check(manifest.nasType == type && manifest.finalPath == target && manifest.sourcePath == source.canonicalPath) {
            "Upload identity changed; reconciliation required"
        }
        withClaim(manifest.id) { initial, token ->
            val claimed = Claimed(initial, token)
            if (initial.state in State.VERIFIED) {
                verifyRemote(initial, initial.finalPath)
                if (source.exists()) verifySource(initial, source, freshDigest)
                return@withClaim
            }
            verifySource(initial, source, freshDigest)
            recoverInterrupted(claimed)
            if (claimed.manifest.state == State.NEW) transferTemporary(claimed, source, progress)
            finalizeTemporary(claimed)
        }
        return VerifiedUploadReceipt(manifest.id)
    }

    /**
     * NAS targets are raw share-relative paths. jcifs resolves names literally (no percent-decoding or
     * fragment parsing), so '#' and '%' are ordinary title characters. ':' would address an NTFS alternate
     * data stream and '?' is a wildcard Windows servers reject.
     */
    private fun validateTarget(target: String) {
        require(target.isNotBlank()) { "NAS target must not be blank" }
        require(!target.startsWith('/') && '\\' !in target) { "NAS target must be share-relative and use '/': $target" }
        require(target.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "NAS target must not contain empty or traversal segments: $target"
        }
        require(target.none { it == ':' || it == '?' || it.isISOControl() }) {
            "NAS target must not contain ':', '?' or control characters: ${target.map { if (it.isISOControl()) '�' else it }.joinToString(
                "",
            )}"
        }
    }

    /** @return the manifest and, when this call fingerprinted the source, the digest it just computed. */
    private fun findOrCreateManifest(
        jobId: Long,
        key: String,
        source: File,
        type: SambaClient.NasType,
        target: String,
    ): Pair<UploadManifest, String?> {
        transaction.execute { manifests.find(jobId, key) }?.let { return it to null }
        val identity = ProcessingIdentity.from(source)
        val digest = source.inputStream().use { it.sha256Hex() }
        // Fingerprint again: a writer active during hashing changes size or mtime.
        check(ProcessingIdentity.from(source) == identity) { "Source changed while fingerprinting" }
        val proposed =
            UploadManifest(
                0,
                jobId,
                key,
                ProcessingIdentity.hash("${type.name}\u0000$target"),
                type,
                target,
                "$target.upload-${UUID.randomUUID()}",
                identity.path,
                identity.size,
                identity.modifiedMillis,
                digest,
                checksumRequired,
                State.NEW,
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
        val manifest =
            transaction.execute { manifests.find(jobId, key) }
                ?: error("Destination belongs to another upload; reconciliation required")
        return manifest to digest
    }

    private fun recoverInterrupted(claimed: Claimed) {
        val current = claimed.manifest
        when (current.state) {
            State.FINALIZING -> error("Ambiguous rename outcome; manual reconciliation required")
            State.CREATING -> {
                // The temporary name is unique to this manifest, so its absence proves no owned bytes exist.
                // A stale or late creation instead makes the next exclusive open fail; it is never adopted.
                check(!nas.exists(current.nasType, current.temporaryPath)) {
                    "Ambiguous temporary creation; manual reconciliation required"
                }
                claimed.transition(State.NEW)
            }
            State.TRANSFERRING -> {
                // Only this state proves exclusive open succeeded. Never delete a merely reserved path.
                nas.delete(current.nasType, current.temporaryPath)
                claimed.transition(State.NEW)
            }
            else -> Unit
        }
    }

    private fun transferTemporary(
        claimed: Claimed,
        source: File,
        progress: (Long, Long) -> Unit,
    ) {
        val current = claimed.manifest
        check(!nas.exists(current.nasType, current.finalPath)) { "Unowned final destination exists" }
        claimed.transition(State.CREATING)
        val sent = nas.transfer(current.nasType, current.temporaryPath, source, { claimed.transition(State.TRANSFERRING) }, progress)
        check(sent == current.sourceSha256) { "Transferred bytes differ from recorded source; local source preserved" }
        checkIdentity(current, ProcessingIdentity.from(source))
        verifyRemote(current, current.temporaryPath)
        claimed.transition(State.TEMP_VERIFIED)
    }

    private fun finalizeTemporary(claimed: Claimed) {
        val current = claimed.manifest
        check(current.state == State.TEMP_VERIFIED) { "Upload requires reconciliation" }
        verifyRemote(current, current.temporaryPath)
        check(!nas.exists(current.nasType, current.finalPath)) { "Unowned final destination exists" }
        claimed.transition(State.FINALIZING)
        nas.finalize(current.nasType, current.temporaryPath, current.finalPath)
        verifyRemote(current, current.finalPath)
        claimed.transition(State.FINALIZED)
    }

    fun register(
        receipt: VerifiedUploadReceipt,
        splittedFileId: Long,
        mime: String?,
        encoding: String?,
    ): CreatedFile =
        withClaim(receipt.manifestId) { manifest, token ->
            check(manifest.state in State.VERIFIED) { "Upload is not verified" }
            verifyRemote(manifest, manifest.finalPath)
            if (manifest.state == State.REGISTERED) {
                val file =
                    transaction.execute { createdMapper.find(checkNotNull(manifest.createdFileId)) }?.toDomain()
                        ?: error("Registered artifact record missing")
                check(
                    file.splittedFileId == splittedFileId &&
                        file.file == manifest.finalPath &&
                        file.size == manifest.sourceSize &&
                        file.mime == mime &&
                        file.encoding == encoding &&
                        file.status == CreatedFile.Status.FILE_MOVED,
                ) { "Registered artifact record does not match this registration" }
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
        val manifest =
            transaction.execute { manifests.find(jobId, ProcessingIdentity.hash(artifact)) } ?: error("Verified archive receipt missing")
        withClaim(manifest.id) { current, _ -> requireRegistered(current) }
        return VerifiedUploadReceipt(manifest.id)
    }

    private fun requireRegistered(manifest: UploadManifest) {
        check(manifest.state == State.REGISTERED) { "Remote artifact has not been registered" }
        val file =
            transaction.execute { createdMapper.find(checkNotNull(manifest.createdFileId)) }?.toDomain()
                ?: error("Registered artifact record missing")
        check(file.file == manifest.finalPath && file.size == manifest.sourceSize && file.status == CreatedFile.Status.FILE_MOVED) {
            "Registered artifact record does not match the upload manifest"
        }
        verifyRemote(manifest, manifest.finalPath)
    }

    /** Call after the processing job has entered RESETTING, before destructive program removal. */
    fun validateReset(jobId: Long) {
        checkNotNull(transaction.execute { manifests.forJob(jobId) }).forEach { manifest ->
            check(manifest.claimToken == null && manifest.state !in State.AMBIGUOUS) {
                "Active or ambiguous upload must be reconciled before reset"
            }
            if (nas.exists(manifest.nasType, manifest.finalPath)) {
                check(manifest.state == State.REGISTERED) { "Unregistered final upload must be reconciled before reset" }
                requireRegistered(manifest)
            }
        }
    }

    /** Explicit cleanup of one durable owned temporary artifact; never scans the share. */
    fun cleanupTemporary(
        jobId: Long,
        artifact: String,
    ) {
        val manifest = transaction.execute { manifests.find(jobId, ProcessingIdentity.hash(artifact)) } ?: return
        withClaim(manifest.id) { current, token ->
            check(current.state == State.NEW || current.state in State.OWNED_TEMPORARY) { "Upload cannot safely clean temporary path" }
            if (current.state != State.NEW) {
                nas.delete(current.nasType, current.temporaryPath)
                Claimed(current, token).transition(State.NEW)
            }
        }
    }

    /** Explicit reset only: no glob/age sweep and no unproven temporary ownership. */
    fun retireAfterReset(jobId: Long) {
        checkNotNull(transaction.execute { manifests.forJob(jobId) }).forEach { entry ->
            withClaim(entry.id) { current, token ->
                check(current.state !in State.AMBIGUOUS) { "Ambiguous upload must be reconciled before reset" }
                check(current.createdFileId == null || transaction.execute { createdMapper.find(current.createdFileId) } == null) {
                    "Registered file row remains; preserve upload ownership"
                }
                check(!nas.exists(current.nasType, current.finalPath)) { "Final artifact remains; preserve upload ownership" }
                if (current.state in State.OWNED_TEMPORARY) nas.delete(current.nasType, current.temporaryPath)
                check(transaction.execute { manifests.remove(current.id, token) } == 1) { "Upload retirement lost ownership" }
            }
        }
    }

    private fun verifyRemote(
        manifest: UploadManifest,
        path: String,
    ) {
        check(nas.size(manifest.nasType, path) == manifest.sourceSize) { "Remote size mismatch; local source preserved" }
        if (manifest.checksumRequired) {
            check(nas.sha256(manifest.nasType, path) == manifest.sourceSha256) { "Remote SHA-256 mismatch" }
        }
    }

    private fun checkIdentity(
        manifest: UploadManifest,
        identity: ProcessingIdentity,
    ) {
        check(
            identity.path == manifest.sourcePath &&
                identity.size == manifest.sourceSize &&
                identity.modifiedMillis == manifest.sourceModified,
        ) { "Local source changed; preserved" }
    }

    /** [knownDigest] is a digest this invocation already computed under the same fingerprint. */
    private fun verifySource(
        manifest: UploadManifest,
        source: File,
        knownDigest: String? = null,
    ) {
        val identity = ProcessingIdentity.from(source)
        checkIdentity(manifest, identity)
        val digest = knownDigest ?: source.inputStream().use { it.sha256Hex() }
        check(digest == manifest.sourceSha256 && ProcessingIdentity.from(source) == identity) { "Local source changed; preserved" }
    }

    private fun <T> withClaim(
        id: Long,
        action: (UploadManifest, String) -> T,
    ): T {
        val token = UUID.randomUUID().toString()
        check(
            transaction.execute {
                manifests.claim(id, token)
            } == 1,
        ) { "Upload already claimed; interrupted owner requires manual reconciliation (docs/verified-uploads.md)" }
        val result =
            try {
                action(checkNotNull(transaction.execute { manifests.get(id) }), token)
            } catch (e: Throwable) {
                // A release failure must not hide why the action failed.
                runCatching { transaction.execute { manifests.release(id, token) } }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
        transaction.execute { manifests.release(id, token) }
        return result
    }
}
