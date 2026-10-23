package me.pinfort.tsvideos.processing.api

import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.VideoResolution
import me.pinfort.tsvideos.processing.domain.ProcessingIdentity
import me.pinfort.tsvideos.processing.domain.UploadManifest
import me.pinfort.tsvideos.processing.domain.UploadManifest.State
import me.pinfort.tsvideos.processing.domain.VerifiedUploadReceipt
import me.pinfort.tsvideos.processing.persistence.UploadManifestMapper
import me.pinfort.tsvideos.storage.api.NasStore
import me.pinfort.tsvideos.storage.api.VerifiedUploadNas
import me.pinfort.tsvideos.storage.api.sha256Hex
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
class VerifiedUploadCommand internal constructor(
    private val manifests: UploadManifestMapper,
    private val nas: VerifiedUploadNas,
    private val createdFiles: CreatedFileCatalog,
    transactionManager: PlatformTransactionManager,
    @Value("\${processor.upload.verify-sha256:false}") private val checksumRequired: Boolean = false,
    private val retry: ProcessingRetry = ProcessingRetry(),
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
            check(transaction.execute { manifests.transition(manifest.id, token, manifest.state, next) } == 1) {
                "Upload ${manifest.id} is no longer ${manifest.state} under this claim; cannot move to $next"
            }
            manifest = manifest.copy(state = next)
        }
    }

    fun upload(
        jobId: Long,
        artifact: String,
        source: File,
        type: NasStore,
        target: String,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): VerifiedUploadReceipt =
        retry.execute(RetryOperation.UPLOAD, {
            retryEligible(
                transaction.execute { manifests.find(jobId, ProcessingIdentity.hash(artifact)) },
                setOf(State.NEW, State.TEMP_VERIFIED, State.FINALIZED, State.REGISTERED) + State.OWNED_TEMPORARY,
            )
        }) {
            uploadOnce(jobId, artifact, source, type, target, progress)
        }

    private fun uploadOnce(
        jobId: Long,
        artifact: String,
        source: File,
        type: NasStore,
        target: String,
        progress: (Long, Long) -> Unit,
    ): VerifiedUploadReceipt {
        require(jobId > 0) { "Upload job id must be positive: $jobId" }
        require(artifact.isNotBlank()) { "Upload artifact name must not be blank" }
        validateTarget(target)
        val manifest = findOrCreateManifest(jobId, ProcessingIdentity.hash(artifact), source, type, target)
        check(manifest.nasType == type && manifest.finalPath == target && manifest.sourcePath == source.canonicalPath) {
            "Upload identity changed; reconciliation required"
        }
        // Content is proven by the digest of the bytes sent (transferTemporary) and re-hashed before local
        // deletion (deleteLocal); retries and repeated callbacks only re-check the size/mtime fingerprint.
        withClaim(manifest.id) { initial, token ->
            val claimed = Claimed(initial, token)
            if (initial.state in State.VERIFIED) {
                verifyRemote(initial, initial.finalPath)
                if (source.exists()) checkIdentity(initial, ProcessingIdentity.from(source))
                return@withClaim
            }
            checkIdentity(initial, ProcessingIdentity.from(source))
            try {
                recoverInterrupted(claimed)
                // A temporary file verified earlier in this call need not be read back again before the rename.
                val resumed = claimed.manifest.state == State.TEMP_VERIFIED
                if (claimed.manifest.state == State.NEW) transferTemporary(claimed, source, progress)
                finalizeTemporary(claimed, reverifyTemporary = resumed)
            } catch (failure: Exception) {
                if (ProcessingCancellation.isInterrupted(failure)) discardOwnedTemporary(claimed, failure)
                throw failure
            }
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
            "NAS target must not contain ':', '?' or control characters: ${printable(target)}"
        }
    }

    private fun printable(value: String): String = value.map { if (it.isISOControl()) '\uFFFD' else it }.joinToString("")

    private fun findOrCreateManifest(
        jobId: Long,
        key: String,
        source: File,
        type: NasStore,
        target: String,
    ): UploadManifest {
        transaction.execute { manifests.find(jobId, key) }?.let { return it }
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
        } catch (lostRace: DuplicateKeyException) {
            // Another invocation inserted first; read its ownership below.
        }
        transaction.execute { manifests.find(jobId, key) }?.let { return it }
        val owner = transaction.execute { manifests.findByDestination(proposed.destinationKey) }
        error("Destination $target belongs to upload ${owner?.id} of job ${owner?.jobId}; reconciliation required")
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
                try {
                    nas.delete(current.nasType, current.temporaryPath)
                    claimed.transition(State.NEW)
                } catch (cleanup: Exception) {
                    cleanup.addSuppressed(RetryRefusedException("Owned temporary cleanup failed; retry refused"))
                    throw cleanup
                }
            }
            else -> Unit
        }
    }

    /** A stop request leaves no owned temporary behind, so the next run starts from NEW instead of reconciling. */
    private fun discardOwnedTemporary(
        claimed: Claimed,
        failure: Exception,
    ) {
        val current = claimed.manifest
        if (current.state !in State.OWNED_TEMPORARY) return
        try {
            ProcessingCancellation.recovery {
                nas.delete(current.nasType, current.temporaryPath)
                claimed.transition(State.NEW)
            }
        } catch (cleanup: Exception) {
            failure.addSuppressed(cleanup)
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

    private fun finalizeTemporary(
        claimed: Claimed,
        reverifyTemporary: Boolean,
    ) {
        val current = claimed.manifest
        check(current.state == State.TEMP_VERIFIED) { "Upload requires reconciliation" }
        if (reverifyTemporary) verifyRemote(current, current.temporaryPath)
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
        resolution: VideoResolution? = null,
    ): CreatedFile =
        retry.execute(RetryOperation.REGISTER_UPLOAD, {
            retryEligible(transaction.execute { manifests.get(receipt.manifestId) }, State.VERIFIED)
        }) {
            registerOnce(receipt, splittedFileId, mime, encoding, resolution)
        }

    private fun registerOnce(
        receipt: VerifiedUploadReceipt,
        splittedFileId: Long,
        mime: String?,
        encoding: String?,
        resolution: VideoResolution?,
    ): CreatedFile =
        withClaim(receipt.manifestId) { manifest, token ->
            check(manifest.state in State.VERIFIED) { "Upload is not verified" }
            verifyRemote(manifest, manifest.finalPath)
            if (manifest.state == State.REGISTERED) {
                val file =
                    transaction.execute { createdFiles.find(checkNotNull(manifest.createdFileId)) }
                        ?: error("Registered artifact record missing")
                check(
                    file.splittedFileId == splittedFileId &&
                        file.file == manifest.finalPath &&
                        file.size == manifest.sourceSize &&
                        (file.mime == mime || file.mime == null) &&
                        file.encoding == encoding &&
                        file.status == CreatedFile.Status.FILE_MOVED,
                ) { "Registered artifact record does not match this registration" }
                // A record registered before its extension had a known mime stores NULL; a re-run fills it in rather than
                // treating the newly guessed mime as a conflict. A stored mime is never overwritten, so if another writer
                // filled it first the stored value must still match this registration.
                val withMime =
                    if (mime != null && file.mime == null) {
                        if (transaction.execute { createdFiles.fillMimeIfMissing(file.id, mime) } != true) {
                            val stored = transaction.execute { createdFiles.find(file.id) }?.mime
                            check(stored == mime) { "Registered artifact record does not match this registration" }
                        }
                        file.copy(mime = mime)
                    } else {
                        file
                    }
                // The resolution is best-effort metadata, so it is not part of this identity check: a re-run that probes
                // null keeps the stored value, and one that probes a value fills in a resolution the first run missed.
                if (resolution != null && withMime.width == null && withMime.height == null) {
                    transaction.execute { createdFiles.fillResolutionIfMissing(file.id, resolution) }
                    withMime.copy(width = resolution.width, height = resolution.height)
                } else {
                    withMime
                }
            } else {
                checkNotNull(
                    transaction.execute {
                        // The catalog insert is MANDATORY: it joins this transaction and commits only with the manifest.
                        val file =
                            createdFiles.register(
                                splittedFileId,
                                manifest.finalPath,
                                manifest.sourceSize,
                                mime,
                                encoding,
                                resolution,
                            )
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
    ): VerifiedUploadReceipt =
        retry.execute(RetryOperation.VERIFY_UPLOAD, {
            retryEligible(transaction.execute { manifests.find(jobId, ProcessingIdentity.hash(artifact)) }, setOf(State.REGISTERED))
        }) { verifyRegisteredOnce(jobId, artifact) }

    private fun verifyRegisteredOnce(
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
            transaction.execute { createdFiles.find(checkNotNull(manifest.createdFileId)) }
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

    /** Uploads of a job that still hold a claim; after a crash these are candidates for [releaseAbandonedClaim]. */
    fun claimed(jobId: Long): List<UploadManifest> {
        val uploads = checkNotNull(transaction.execute { manifests.forJob(jobId) })
        return uploads.filter { it.claimToken != null }
    }

    /**
     * Operator recovery once every processor/callback process is stopped: clears exactly the claim that was inspected.
     * State and NAS are untouched, so the next run resumes or asks for reconciliation as usual.
     */
    fun releaseAbandonedClaim(manifest: UploadManifest) {
        val token = checkNotNull(manifest.claimToken) { "Upload ${manifest.id} is not claimed" }
        check(transaction.execute { manifests.release(manifest.id, token) } == 1) {
            "Claim of upload ${manifest.id} changed since it was inspected; inspect again"
        }
    }

    /** Explicit reset only: no glob/age sweep and no unproven temporary ownership. */
    fun retireAfterReset(jobId: Long) {
        checkNotNull(transaction.execute { manifests.forJob(jobId) }).forEach { entry ->
            withClaim(entry.id, releaseAfterSuccess = false) { current, token ->
                check(current.state !in State.AMBIGUOUS) { "Ambiguous upload must be reconciled before reset" }
                check(current.createdFileId == null || transaction.execute { createdFiles.find(current.createdFileId) } == null) {
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

    /** Full content re-check; only required immediately before deleting the local source. */
    private fun verifySource(
        manifest: UploadManifest,
        source: File,
    ) {
        val identity = ProcessingIdentity.from(source)
        checkIdentity(manifest, identity)
        val digest = source.inputStream().use { it.sha256Hex() }
        check(digest == manifest.sourceSha256 && ProcessingIdentity.from(source) == identity) { "Local source changed; preserved" }
    }

    private fun retryEligible(
        manifest: UploadManifest?,
        states: Set<State>,
    ): Boolean = manifest != null && manifest.claimToken == null && manifest.state in states

    private fun <T> withClaim(
        id: Long,
        releaseAfterSuccess: Boolean = true,
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
                // A release failure must not hide why the action failed. An interrupted owner still releases.
                runCatching { ProcessingCancellation.recovery { release(id, token) } }
                    .exceptionOrNull()
                    ?.let {
                        e.addSuppressed(it)
                        e.addSuppressed(RetryRefusedException("Upload claim release unconfirmed; retry refused"))
                    }
                throw e
            }
        // Deliberately not swallowed: a caller must not continue as if the claim were free. The completed state is
        // durable, so after `tvpcli release-claim` the next call resumes from it instead of redoing the work.
        // A successful retire removes the row under this token, so there is nothing left to release.
        if (releaseAfterSuccess) {
            try {
                ProcessingCancellation.recovery { release(id, token) }
            } catch (releaseFailure: Exception) {
                releaseFailure.addSuppressed(RetryRefusedException("Upload claim release unconfirmed; retry refused"))
                throw releaseFailure
            }
        }
        return result
    }

    private fun release(
        id: Long,
        token: String,
    ) {
        check(transaction.execute { manifests.release(id, token) } == 1) { "Upload claim release lost ownership" }
    }
}
