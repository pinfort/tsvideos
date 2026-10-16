package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.component.DirectoryNameComponent
import me.pinfort.tsvideos.core.component.NormalizeComponent
import me.pinfort.tsvideos.core.domain.BulkAction
import me.pinfort.tsvideos.core.domain.BulkApplyRequest
import me.pinfort.tsvideos.core.domain.BulkOutcome
import me.pinfort.tsvideos.core.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.core.domain.BulkPlan
import me.pinfort.tsvideos.core.domain.BulkRequest
import me.pinfort.tsvideos.core.domain.BulkResult
import me.pinfort.tsvideos.core.domain.BulkSnapshot
import me.pinfort.tsvideos.core.domain.BulkTarget
import me.pinfort.tsvideos.core.domain.InvalidBulkRequestException
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.external.database.dto.CreatedFileDto
import me.pinfort.tsvideos.core.external.database.mapper.BulkLibraryMapper
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ExecutedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.SplittedFileMapper
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
import java.util.Locale

@Component
class BulkLibraryCommand(
    private val programs: ProgramCommand,
    private val executed: ExecutedFileMapper,
    private val splits: SplittedFileMapper,
    private val created: CreatedFileMapper,
    private val bulk: BulkLibraryMapper,
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
        return read.execute {
            val context = Context(bulk.allFiles(), proposed(request))
            BulkPlan(request, request.ids.map { target(it, request, context) })
        }!!
    }

    fun apply(input: BulkApplyRequest): BulkResult {
        val request = validate(input.request)
        ensure(input.fingerprints.keys == request.ids.toSet()) { "Every target requires its preview fingerprint" }
        ensure(input.fingerprints.values.all { it.matches(fingerprintPattern) }) { "Invalid fingerprint" }
        // Selection-wide destination counts are computed once: other programs' existing paths are still
        // re-checked per target against the locked alias scan, and any change to a target's own files is STALE.
        val proposed = read.execute { proposed(request) }!!
        return BulkResult(request.ids.map { id -> applyOne(id, request, proposed, input.fingerprints.getValue(id)) })
    }

    private fun applyOne(
        id: Long,
        request: BulkRequest,
        proposed: Map<String, Int>,
        fingerprint: String,
    ): BulkOutcome {
        // Set just before the first NAS/DB mutation, so a lock failure is only reported as harmless before it.
        var mutating = false
        return try {
            write.execute {
                // Same lock order for every bulk operation. Do not hold locks across targets.
                val all = bulk.lockFiles()
                val locked = bulk.lockProgram(id)
                val program = locked?.let { programs.find(it) }
                if (program == null) {
                    return@execute BulkOutcome(id, BulkOutcomeStatus.NOT_FOUND, "Recording no longer exists")
                }
                bulk.lockExecuted(program.executedFileId)
                bulk.lockSplits(program.executedFileId)
                bulk.lockReferences(program.executedFileId)
                val current = target(id, request, Context(all, proposed))
                when {
                    current.fingerprint != fingerprint ->
                        BulkOutcome(id, BulkOutcomeStatus.STALE, "Recording changed; preview again")
                    !current.eligible -> BulkOutcome(id, BulkOutcomeStatus.FAILED, current.reason!!)
                    else -> {
                        mutating = true
                        when (request.action) {
                            BulkAction.DELETE -> programs.delete(program)
                            BulkAction.MOVE -> programs.moveCreatedFiles(program, request.directory!!)
                        }
                        BulkOutcome(id, BulkOutcomeStatus.SUCCESS, "Completed; original recording preserved")
                    }
                }
            }!!
        } catch (error: PessimisticLockingFailureException) {
            if (mutating) return failedAfterMutation(id, request, error)
            logger.warn("Bulk ${request.action} skipped, library busy, programId=$id", error)
            BulkOutcome(id, BulkOutcomeStatus.FAILED, "Library is busy (processing may be running); nothing was changed. Retry later.")
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            if (mutating) return failedAfterMutation(id, request, error)
            logger.error("Bulk ${request.action} failed before any change, programId=$id", error)
            BulkOutcome(id, BulkOutcomeStatus.FAILED, "Operation failed before any change; nothing was changed. Preview again and retry.")
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
        ensure(safeLabel(directory)) { "Invalid directory label" }
        val normalized = normalize.normalize(directory.trim())
        // Normalization must never introduce a character the raw check would have refused.
        ensure(safeLabel(normalized) && safeSegment(normalized)) { "Invalid normalized directory" }
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

    /** Per-request data shared by every target, so each target costs a constant number of queries instead of O(N). */
    private inner class Context(
        all: List<CreatedFileDto>,
        val proposed: Map<String, Int>,
    ) {
        // Normalized path key -> created_file ids registered at that path (not program ids).
        val owners: Map<String, Set<Long>> = all.groupBy({ key(it.file) }, { it.id }).mapValues { it.value.toSet() }
    }

    private fun proposed(request: BulkRequest): Map<String, Int> =
        if (request.action == BulkAction.MOVE) {
            bulk
                .createdFilesOfPrograms(request.ids)
                .filter { safePath(it.file) }
                .map { key(destination(it, request.directory!!)) }
                .groupingBy { it }
                .eachCount()
        } else {
            emptyMap()
        }

    private fun destination(
        file: CreatedFileDto,
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
    ): BulkTarget {
        val program = programs.find(id)
        val snapshot =
            program?.let {
                BulkSnapshot(
                    it,
                    executed.find(it.executedFileId),
                    splits.selectByExecutedFileId(it.executedFileId).sortedBy { f -> f.id },
                    created.selectByExecutedFileId(it.executedFileId).sortedBy { f -> f.id },
                )
            }
        val files = snapshot?.createdFiles.orEmpty()
        val destinations =
            if (request.action == BulkAction.MOVE && files.all { safePath(it.file) }) {
                files.associate { it.id to destination(it, request.directory!!) }
            } else {
                emptyMap()
            }
        val refs = program?.let { bulk.references(it.executedFileId) }.orEmpty()
        val ownIds = files.map { it.id }.toSet()
        val shared = files.any { file -> context.owners[key(file.file)].orEmpty().any { it !in ownIds } }
        val duplicate = files.map { key(it.file) }.distinct().size != files.size
        val destinationKeys = destinations.mapValues { key(it.value) }
        val proposedTwice = destinationKeys.values.any { context.proposed[it] != 1 }
        val alreadyReferenced = destinationKeys.any { (fileId, k) -> context.owners[k].orEmpty().any { it != fileId } }
        val collision = proposedTwice || alreadyReferenced || destinationKeys.values.distinct().size != destinationKeys.size
        val unchanged = files.any { destinationKeys[it.id] == key(it.file) }
        val reason =
            when {
                snapshot == null -> "Recording not found"
                program.status != Program.Status.COMPLETED -> "Only COMPLETED recordings can be changed"
                snapshot.executed == null -> "Original recording metadata missing"
                refs.any { it != id } || shared || duplicate -> "Shared file paths or recording metadata; operation refused"
                files.isEmpty() && request.action == BulkAction.MOVE -> "No generated files to move"
                files.any { it.status != CreatedFileDto.Status.FILE_MOVED } -> "Unfinished generated files; operation refused"
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
     * Explicit field-by-field serialization: adding a field to a DTO must be reflected here
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
                        s.createdFiles.map { listOf(it.id, it.splittedFileId, it.file, it.size, it.mime, it.encoding, it.status) },
                    )
                },
                destinations.toSortedMap().map { (k, v) -> listOf(k, v) },
                refs,
                listOf(shared, duplicate, collision),
            )
        encode(fields)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun key(path: String) = path.replace('\\', '/').split('/').joinToString("/") { it.trimEnd(' ', '.').lowercase(Locale.ROOT) }

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
}
