package me.pinfort.tsvideos.library.api

import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.catalog.api.CreatedFilePathKey
import me.pinfort.tsvideos.catalog.api.CreatedFileSnapshot
import me.pinfort.tsvideos.catalog.api.DirectoryNameComponent
import me.pinfort.tsvideos.catalog.api.LedgerLocks
import me.pinfort.tsvideos.catalog.api.LedgerSnapshots
import me.pinfort.tsvideos.catalog.api.NormalizeComponent
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.api.ProgramSnapshot
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.library.domain.BulkAction
import me.pinfort.tsvideos.library.domain.BulkApplyRequest
import me.pinfort.tsvideos.library.domain.BulkInterruptedException
import me.pinfort.tsvideos.library.domain.BulkOutcome
import me.pinfort.tsvideos.library.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.library.domain.BulkPlan
import me.pinfort.tsvideos.library.domain.BulkRequest
import me.pinfort.tsvideos.library.domain.BulkResult
import me.pinfort.tsvideos.library.domain.BulkSnapshot
import me.pinfort.tsvideos.library.domain.BulkTarget
import me.pinfort.tsvideos.library.domain.InvalidBulkRequestException
import org.slf4j.Logger
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime

@Component
class BulkLibraryCommand(
    private val programs: ProgramCommand,
    private val snapshots: LedgerSnapshots,
    private val locks: LedgerLocks,
    private val createdFiles: CreatedFileCatalog,
    private val directories: DirectoryNameComponent,
    private val normalize: NormalizeComponent,
    transactionManager: PlatformTransactionManager,
    private val logger: Logger,
) {
    private val read =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }
    private val write =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    private val fingerprintPattern = Regex("[a-f0-9]{64}")

    // base/index/program/file: DirectoryNameComponent replaces the program directory, so shorter paths are refused.
    private val minNasPathSegments = 4

    fun preview(input: BulkRequest): BulkPlan {
        val request = validate(input)
        fillPathKeys()
        return plan(request)
    }

    /** [preview] without filling in missing path keys, for a caller that already runs inside its own transaction (cleanup). */
    internal fun previewInTransaction(input: BulkRequest): BulkPlan = plan(validate(input))

    private fun plan(request: BulkRequest): BulkPlan =
        read.execute {
            val files = snapshots.createdFilesOfPrograms(request.ids)
            // Only the paths of the selection and their destinations are looked up, never the whole table.
            val paths = pathsOf(files, request)
            val context = Context(snapshots.createdFilesAt(paths), paths, proposed(request, files))
            BulkPlan(request, request.ids.map { target(it, request, context, snapshots.programSnapshot(it)) })
        }

    fun apply(input: BulkApplyRequest): BulkResult = applyGuarded(input, null)

    /**
     * [guard] runs inside each target's transaction after every program/executed/split row is locked, and may
     * veto the target (it receives the IDs this call has already deleted successfully). Guarded callers are
     * operator CLIs: an interrupt aborts the whole call with [BulkInterruptedException], carrying the outcomes
     * finished so far, instead of reporting the remaining targets as FAILED.
     */
    internal fun applyGuarded(
        input: BulkApplyRequest,
        guard: ((Long, Set<Long>) -> BulkOutcome?)?,
    ): BulkResult {
        val request = validate(input.request)
        ensure(input.fingerprints.keys == request.ids.toSet()) { "Every target requires its preview fingerprint" }
        ensure(input.fingerprints.values.all { it.matches(fingerprintPattern) }) { "Invalid fingerprint" }
        fillPathKeys()
        // Selection-wide destination counts are computed once: other programs' existing paths are still
        // re-checked per target under its path guards, and any change to a target's own files is STALE.
        val proposed = read.execute { proposed(request, snapshots.createdFilesOfPrograms(request.ids)) }
        val successful = mutableSetOf<Long>()
        val outcomes = mutableListOf<BulkOutcome>()
        for (id in request.ids) {
            // After an interrupt (e.g. shutdown) stop starting new targets rather than mutating more files.
            val outcome =
                if (Thread.currentThread().isInterrupted) {
                    if (guard != null) throw BulkInterruptedException(outcomes.toList(), null)
                    BulkOutcome(id, BulkOutcomeStatus.FAILED, "Interrupted before this target; nothing was changed.")
                } else {
                    try {
                        applyOne(id, request, proposed, input.fingerprints.getValue(id), guard, successful.toSet())
                    } catch (error: InterruptedException) {
                        throw BulkInterruptedException(outcomes.toList(), id).apply { initCause(error) }
                    }
                }
            if (outcome.status == BulkOutcomeStatus.SUCCESS) successful.add(id)
            outcomes.add(outcome)
        }
        return BulkResult(outcomes)
    }

    private fun applyOne(
        id: Long,
        request: BulkRequest,
        proposed: Map<String, Int>,
        fingerprint: String,
        guard: ((Long, Set<Long>) -> BulkOutcome?)?,
        successful: Set<Long>,
    ): BulkOutcome {
        // Set just before the first NAS/DB mutation, so a lock failure is only reported as harmless before it.
        var mutating = false
        return try {
            // Read in a transaction of its own: the target's transaction must take its path guards before its first
            // plain read fixes the REPEATABLE_READ snapshot (LedgerLocks.lockCreatedFilePaths).
            val plannedSnapshot = read.execute { snapshots.programSnapshot(id) }
            val planned = plannedSnapshot?.createdFiles.orEmpty()
            val plannedPaths = pathsOf(planned, request)
            val plannedIds = planned.map { it.id }.toSet()
            val plannedSplits =
                plannedSnapshot
                    ?.splitFiles
                    .orEmpty()
                    .map { it.id }
                    .toSet()
            write.execute {
                // Same lock order for every bulk operation (see LedgerLocks). Do not hold locks across targets.
                // The split guards hold up registrations on the target's splits (at any path) before they write.
                locks.lockCreatedFilePaths(plannedPaths, plannedSplits)
                if (guard != null) {
                    locks.lockCreatedFiles()
                    locks.lockWholeLedger()
                    guard(id, successful)?.let { return@execute it }
                } else {
                    locks.lockCreatedFileRows(plannedIds)
                }
                val program =
                    locks.lockProgramGraph(id)
                        ?: return@execute BulkOutcome(id, BulkOutcomeStatus.NOT_FOUND, "Recording no longer exists")
                val snapshot = snapshots.programSnapshot(id)
                val files = snapshot?.createdFiles.orEmpty()
                val paths = pathsOf(files, request)
                // A file added or renamed, or a split added, since the read above is not covered by the guards and row
                // locks just taken.
                val lockedKeys = plannedPaths.map { key(it) }.toSet()
                if (!lockedKeys.containsAll(paths.map { key(it) }) ||
                    !plannedIds.containsAll(files.map { it.id }) ||
                    !plannedSplits.containsAll(snapshot?.splitFiles.orEmpty().map { it.id })
                ) {
                    return@execute BulkOutcome(id, BulkOutcomeStatus.STALE, "Recording changed; preview again")
                }
                val current = target(id, request, Context(snapshots.createdFilesAt(paths), paths, proposed), snapshot)
                when {
                    current.fingerprint != fingerprint ->
                        BulkOutcome(id, BulkOutcomeStatus.STALE, "Recording changed; preview again")
                    !current.eligible ->
                        BulkOutcome(
                            id,
                            BulkOutcomeStatus.FAILED,
                            current.reason ?: "Recording is not eligible for this operation",
                        )
                    else -> {
                        if (guard != null && Thread.currentThread().isInterrupted) {
                            throw InterruptedException("Bulk operation interrupted")
                        }
                        mutating = true
                        when (request.action) {
                            BulkAction.DELETE -> programs.delete(program)
                            BulkAction.MOVE ->
                                programs.moveCreatedFiles(
                                    program,
                                    requireNotNull(request.directory) { "MOVE requires a directory" },
                                )
                        }
                        BulkOutcome(id, BulkOutcomeStatus.SUCCESS, "Completed; original recording preserved")
                    }
                }
            }
        } catch (error: PessimisticLockingFailureException) {
            if (guard != null) rethrowIfInterrupted(id, request, error, mutating)
            if (mutating) return failedAfterMutation(id, request, error)
            logger.warn("Bulk ${request.action} skipped, library busy, programId=$id", error)
            BulkOutcome(id, BulkOutcomeStatus.FAILED, "Library is busy (processing may be running); nothing was changed. Retry later.")
        } catch (error: Exception) {
            // TransactionTemplate and CreatedFileCommand wrap checked exceptions, so look through the cause chain.
            if (generateSequence<Throwable>(error) { it.cause }.any { it is InterruptedException }) {
                Thread.currentThread().interrupt()
            }
            if (guard != null) rethrowIfInterrupted(id, request, error, mutating)
            if (mutating) return failedAfterMutation(id, request, error)
            logger.error("Bulk ${request.action} failed before any change, programId=$id", error)
            BulkOutcome(id, BulkOutcomeStatus.FAILED, "Operation failed before any change; nothing was changed. Preview again and retry.")
        }
    }

    /**
     * Guarded callers stop on interrupt; the original failure stays attached as the cause. TransactionTemplate
     * wraps a checked InterruptedException, so the cause chain is checked as well as the thread flag.
     */
    private fun rethrowIfInterrupted(
        id: Long,
        request: BulkRequest,
        error: Exception,
        mutating: Boolean,
    ) {
        if (generateSequence<Throwable>(error) { it.cause }.any { it is InterruptedException }) {
            Thread.currentThread().interrupt()
        }
        if (Thread.currentThread().isInterrupted) {
            logger.error("Bulk ${request.action} interrupted, programId=$id, afterMutationStarted=$mutating", error)
            throw InterruptedException("Bulk operation interrupted").apply { initCause(error) }
        }
    }

    private fun failedAfterMutation(
        id: Long,
        request: BulkRequest,
        error: Exception,
    ): BulkOutcome {
        logger.error("Bulk ${request.action} failed, programId=$id, directory=${request.directory}", error)
        return BulkOutcome(
            id,
            BulkOutcomeStatus.FAILED,
            "Operation failed. NAS files may be partially deleted or moved; inspect preview paths before retry. Original recording preserved.",
        )
    }

    private fun validate(input: BulkRequest): BulkRequest {
        val idsValid = input.ids.size in 1..100 && input.ids.all { it > 0 } && input.ids.distinct().size == input.ids.size
        ensure(idsValid) { "Select 1 to 100 unique positive IDs" }
        if (input.action == BulkAction.DELETE) {
            ensure(input.directory == null) { "DELETE does not accept a directory" }
            return input.copy(ids = input.ids.sorted())
        }
        val directory = input.directory ?: throw InvalidBulkRequestException("MOVE requires a directory")
        ensure(
            safeLabel(directory),
        ) { "Invalid directory label: it must not be blank, \".\" or \"..\", or contain / \\ # % or control characters" }
        val normalized = normalize.normalize(directory.trim())
        // Normalization must never introduce a character the raw check would have refused.
        ensure(safeLabel(normalized) && safeSegment(normalized)) {
            "Invalid directory label: it must not end with a dot or space"
        }
        return input.copy(ids = input.ids.sorted(), directory = normalized)
    }

    private inline fun ensure(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw InvalidBulkRequestException(message())
    }

    private fun safeLabel(label: String): Boolean =
        label.isNotBlank() &&
            label != "." &&
            label != ".." &&
            label.none { it == '/' || it == '\\' || it == '#' || it == '%' || it.isISOControl() }

    /**
     * Per-request data shared by every target, so each target costs a constant number of queries instead of O(N).
     * [files] are the rows at [paths] (LedgerSnapshots.createdFilesAt); a path outside them was never looked up.
     */
    private inner class Context(
        files: List<CreatedFileSnapshot>,
        paths: Collection<String>,
        val proposed: Map<String, Int>,
    ) {
        private val covered = paths.map { key(it) }.toSet()

        // Normalized path key -> created_file ids registered at that path (not program ids).
        private val byKey: Map<String, Set<Long>> = files.groupBy({ key(it.file) }, { it.id }).mapValues { it.value.toSet() }

        fun owners(key: String): Set<Long> {
            check(key in covered) { "Path was not looked up for this bulk request" }
            return byKey[key].orEmpty()
        }
    }

    /** Every path [files] occupy, plus each safe file's destination for a MOVE: the paths an operation would touch. */
    private fun pathsOf(
        files: List<CreatedFileSnapshot>,
        request: BulkRequest,
    ): List<String> {
        val destinations =
            if (request.action == BulkAction.MOVE) {
                val directory = requireNotNull(request.directory) { "MOVE requires a directory" }
                files.filter { safePath(it.file) }.map { destination(it, directory) }
            } else {
                emptyList()
            }
        return (files.map { it.file } + destinations).distinct()
    }

    private fun proposed(
        request: BulkRequest,
        files: List<CreatedFileSnapshot>,
    ): Map<String, Int> =
        if (request.action == BulkAction.MOVE) {
            files
                .filter { safePath(it.file) }
                .map { key(destination(it, requireNotNull(request.directory) { "MOVE requires a directory" })) }
                .groupingBy { it }
                .eachCount()
        } else {
            emptyMap()
        }

    /**
     * Stores the path keys of rows written without one, in short transactions of their own and outside any target's.
     * Best effort: a row without a key is still compared by [LedgerSnapshots.createdFilesAt], only not by index.
     */
    private fun fillPathKeys() {
        try {
            var cursor = 0L
            while (true) {
                val next = write.execute { createdFiles.fillMissingPathKeys(cursor, PATH_KEY_CHUNK_SIZE) } ?: break
                if (next <= cursor) break
                cursor = next
            }
        } catch (error: Exception) {
            if (generateSequence<Throwable>(error) { it.cause }.any { it is InterruptedException }) {
                Thread.currentThread().interrupt()
            }
            logger.warn("Could not fill in created_file path keys; unkeyed rows are still checked, without the index", error)
        }
    }

    private fun destination(
        file: CreatedFileSnapshot,
        directory: String,
    ): String =
        directories
            .replaceWithGivenDirectoryName(Path.of(file.file.replace('\\', '/')), directory)
            .toString()
            .replace('/', '\\')

    private fun target(
        id: Long,
        request: BulkRequest,
        context: Context,
        programSnapshot: ProgramSnapshot?,
    ): BulkTarget {
        val snapshot = programSnapshot?.let { BulkSnapshot(it) }
        val program = snapshot?.program
        val files = snapshot?.createdFiles.orEmpty()
        val destinations =
            if (request.action == BulkAction.MOVE && files.all { safePath(it.file) }) {
                files.associate { it.id to destination(it, requireNotNull(request.directory) { "MOVE requires a directory" }) }
            } else {
                emptyMap()
            }
        val refs = program?.let { snapshots.referencingPrograms(it.executedFileId) }.orEmpty()
        val ownIds = files.map { it.id }.toSet()
        val shared = files.any { file -> context.owners(key(file.file)).any { it !in ownIds } }
        val duplicate = files.map { key(it.file) }.distinct().size != files.size
        val destinationKeys = destinations.mapValues { key(it.value) }
        val proposedTwice = destinationKeys.values.any { context.proposed[it] != 1 }
        val alreadyReferenced = destinationKeys.any { (fileId, k) -> context.owners(k).any { it != fileId } }
        val collision = proposedTwice || alreadyReferenced || destinationKeys.values.distinct().size != destinationKeys.size
        val unchanged = files.any { destinationKeys[it.id] == key(it.file) }
        val reason =
            when {
                snapshot == null -> "Recording not found"
                snapshot.program.status != Program.Status.COMPLETED -> "Only COMPLETED recordings can be changed"
                snapshot.executed == null -> "Original recording metadata missing"
                refs.any { it != id } || shared || duplicate -> "Shared file paths or recording metadata; operation refused"
                files.isEmpty() && request.action == BulkAction.MOVE -> "No generated files to move"
                files.any { it.status != CreatedFile.Status.FILE_MOVED } -> "Unfinished generated files; operation refused"
                files.any { !safePath(it.file) || it.size < 0 } -> "Unsafe NAS path; operation refused"
                collision -> "Destination is already referenced or duplicated"
                unchanged -> "Destination is unchanged"
                else -> null
            }
        val eligible = reason == null
        val fingerprint = fingerprint(request, snapshot, destinations, refs, shared = shared, duplicate = duplicate, collision = collision)
        // Registered bytes this target would delete (DELETE) or transfer (MOVE); ineligible targets contribute nothing.
        val registeredBytes = if (eligible) files.sumOf { it.size } else 0
        return BulkTarget(id, snapshot, fingerprint, destinations, eligible, reason, registeredBytes)
    }

    /**
     * Explicit field-by-field serialization: adding a field to a snapshot type must be reflected here
     * deliberately, and no value relies on an implementation-defined toString().
     * Values are length-prefixed so user text cannot forge delimiters.
     */
    private fun fingerprint(
        request: BulkRequest,
        snapshot: BulkSnapshot?,
        destinations: Map<Long, String>,
        refs: List<Long>,
        shared: Boolean,
        duplicate: Boolean,
        collision: Boolean,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val output = DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest))

        fun encode(item: Any?) {
            when (item) {
                null -> output.writeByte(0)
                is List<*> -> {
                    output.writeByte(1)
                    output.writeInt(item.size)
                    item.forEach(::encode)
                }
                is String -> {
                    val bytes = item.toByteArray(Charsets.UTF_8)
                    output.writeByte(2)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
                is Long -> output.apply { writeByte(3) }.writeLong(item)
                is Int -> output.apply { writeByte(4) }.writeInt(item)
                is Double -> output.apply { writeByte(5) }.writeDouble(item)
                is Boolean -> output.apply { writeByte(6) }.writeBoolean(item)
                is Enum<*> -> encode(item.name)
                is LocalDateTime -> encode(item.toString())
                else -> error("Unsupported fingerprint value: ${item::class}")
            }
        }

        val fields =
            listOf(
                request.action,
                request.directory,
                snapshot?.let { s ->
                    listOf(
                        s.program.let {
                            listOf(
                                it.id,
                                it.name,
                                it.executedFileId,
                                it.status,
                                it.drops,
                                it.size,
                                it.recordedAt,
                                it.channel,
                                it.title,
                                it.channelName,
                                it.duration,
                            )
                        },
                        s.executed?.let {
                            listOf(
                                it.id,
                                it.file,
                                it.drops,
                                it.size,
                                it.recordedAt,
                                it.channel,
                                it.title,
                                it.channelName,
                                it.duration,
                                it.status,
                            )
                        },
                        s.splitFiles.map { listOf(it.id, it.executedFileId, it.file, it.size, it.duration, it.status) },
                        s.createdFiles.map {
                            listOf(
                                it.id,
                                it.splittedFileId,
                                it.file,
                                it.size,
                                it.mime,
                                it.encoding,
                                it.status,
                                it.width,
                                it.height,
                            )
                        },
                    )
                },
                destinations.toSortedMap().map { (k, v) -> listOf(k, v) },
                refs,
                listOf(shared, duplicate, collision),
            )
        encode(fields)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun key(path: String) = CreatedFilePathKey.of(path)

    // Stricter than NasComponent.validateFilePath on purpose: bulk refuses URL-special and alias-prone names
    // that single-program delete still accepts.
    private fun safePath(path: String): Boolean {
        val segments = path.replace('\\', '/').split('/')
        return !path.startsWith('/') && !path.startsWith('\\') && segments.size >= minNasPathSegments && segments.all(::safeSegment)
    }

    private fun safeSegment(segment: String): Boolean =
        segment.isNotBlank() &&
            segment != "." &&
            segment != ".." &&
            !segment.endsWith('.') &&
            !segment.endsWith(' ') &&
            segment.none { c -> c in ":?#%" || c.isISOControl() }

    private companion object {
        const val PATH_KEY_CHUNK_SIZE = 500
    }
}
