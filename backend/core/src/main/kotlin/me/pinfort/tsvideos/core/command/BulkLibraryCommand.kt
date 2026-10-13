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
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.external.database.dto.CreatedFileDto
import me.pinfort.tsvideos.core.external.database.mapper.BulkLibraryMapper
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ExecutedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.SplittedFileMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.security.MessageDigest
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

    fun preview(input: BulkRequest): BulkPlan {
        val request = validate(input)
        return read.execute {
            val all = bulk.allFiles()
            BulkPlan(request, request.ids.map { target(it, request, all) })
        }!!
    }

    fun apply(input: BulkApplyRequest): BulkResult = applyGuarded(input, null)

    internal fun applyGuarded(
        input: BulkApplyRequest,
        guard: ((Long, Set<Long>) -> BulkOutcome?)?,
    ): BulkResult {
        val request = validate(input.request)
        require(input.fingerprints.keys == request.ids.toSet()) { "Every target requires its preview fingerprint" }
        require(input.fingerprints.values.all { it.matches(Regex("[a-f0-9]{64}")) }) { "Invalid fingerprint" }
        val successful = mutableSetOf<Long>()
        return BulkResult(
            request.ids.map { id ->
                val outcome =
                    try {
                        write.execute {
                            // Same lock order for every bulk operation. Do not hold locks across targets.
                            val all = bulk.lockFiles()
                            if (guard != null) {
                                bulk.lockAllPrograms()
                                bulk.lockAllExecuted()
                                bulk.lockAllSplits()
                                guard(id, successful.toSet())?.let { return@execute it }
                            }
                            if (bulk.lockProgram(id) ==
                                null
                            ) {
                                return@execute BulkOutcome(id, BulkOutcomeStatus.NOT_FOUND, "Recording no longer exists")
                            }
                            val program = programs.find(id)!!
                            bulk.lockExecuted(program.executedFileId)
                            bulk.lockSplits(program.executedFileId)
                            bulk.lockReferences(program.executedFileId)
                            val current = target(id, request, all)
                            when {
                                current.fingerprint != input.fingerprints[id] ->
                                    BulkOutcome(
                                        id,
                                        BulkOutcomeStatus.STALE,
                                        "Recording changed; preview again",
                                    )
                                !current.eligible -> BulkOutcome(id, BulkOutcomeStatus.FAILED, current.reason!!)
                                else -> {
                                    when (request.action) {
                                        BulkAction.DELETE -> programs.delete(program)
                                        BulkAction.MOVE -> programs.moveCreatedFiles(program, request.directory!!)
                                    }
                                    BulkOutcome(id, BulkOutcomeStatus.SUCCESS, "Completed; original recording preserved")
                                }
                            }
                        }!!
                    } catch (_: Exception) {
                        BulkOutcome(
                            id,
                            BulkOutcomeStatus.FAILED,
                            "Operation failed. NAS files may be partially deleted or moved; inspect preview paths before retry. Original recording preserved.",
                        )
                    }
                if (outcome.status == BulkOutcomeStatus.SUCCESS) successful.add(id)
                outcome
            },
        )
    }

    private fun validate(input: BulkRequest): BulkRequest {
        require(
            input.ids.size in 1..100 &&
                input.ids.all {
                    it > 0
                } &&
                input.ids.distinct().size == input.ids.size,
        ) { "Select 1 to 100 unique positive IDs" }
        if (input.action == BulkAction.DELETE) {
            require(input.directory == null) { "DELETE does not accept a directory" }
            return input.copy(ids = input.ids.sorted())
        }
        val directory = input.directory ?: throw IllegalArgumentException("MOVE requires a directory")
        require(
            directory.isNotBlank() &&
                directory.none { it == '/' || it == '\\' || it == '#' || it == '%' || it.isISOControl() } &&
                directory != "." &&
                directory != "..",
        ) { "Invalid directory label" }
        val normalized = normalize.normalize(directory.trim())
        require(
            normalized.isNotBlank() && normalized != "." && normalized != ".." && !normalized.endsWith('.') && !normalized.endsWith(' '),
        ) {
            "Invalid normalized directory"
        }
        return input.copy(ids = input.ids.sorted(), directory = normalized)
    }

    private fun target(
        id: Long,
        request: BulkRequest,
        all: List<CreatedFileDto>,
    ): BulkTarget {
        val program = programs.find(id)
        val snapshot =
            program?.let {
                BulkSnapshot(
                    it,
                    executed.find(it.executedFileId),
                    splits.selectByExecutedFileId(it.executedFileId).sortedBy { f ->
                        f.id
                    },
                    created.selectByExecutedFileId(it.executedFileId).sortedBy { f -> f.id },
                )
            }
        val files = snapshot?.createdFiles.orEmpty()
        val destinations =
            if (request.action == BulkAction.MOVE && files.all { safePath(it.file) }) {
                files.associate {
                    it.id to
                        directories
                            .replaceWithGivenDirectoryName(
                                Path.of(it.file.replace('\\', '/')),
                                request.directory!!,
                            ).toString()
                            .replace('/', '\\')
                }
            } else {
                emptyMap()
            }
        val refs = program?.let { bulk.references(it.executedFileId) }.orEmpty()
        val ownIds = files.map { it.id }.toSet()
        val shared = files.any { file -> all.any { it.id !in ownIds && key(it.file) == key(file.file) } }
        val duplicate = files.map { key(it.file) }.distinct().size != files.size
        val proposed =
            if (request.action == BulkAction.MOVE) {
                request.ids
                    .mapNotNull { programs.find(it) }
                    .flatMap { created.selectByExecutedFileId(it.executedFileId) }
                    .filter { safePath(it.file) }
                    .map {
                        key(directories.replaceWithGivenDirectoryName(Path.of(it.file.replace('\\', '/')), request.directory!!).toString())
                    }.groupingBy { it }
                    .eachCount()
            } else {
                emptyMap()
            }
        val collision =
            destinations.values.any { proposed[key(it)] != 1 } ||
                destinations.any { (id, path) -> all.any { it.id != id && key(it.file) == key(path) } } ||
                destinations.values
                    .map(::key)
                    .distinct()
                    .size != destinations.size
        val reason =
            when {
                snapshot == null -> "Recording not found"
                program.status != Program.Status.COMPLETED -> "Only COMPLETED recordings can be changed"
                snapshot.executed == null -> "Original recording metadata missing"
                refs.any { it != id } || shared || duplicate -> "Shared file paths or recording metadata; operation refused"
                files.any { it.status != CreatedFileDto.Status.FILE_MOVED } -> "Unfinished generated files; operation refused"
                files.any { !safePath(it.file) || it.size < 0 } -> "Unsafe NAS path; operation refused"
                collision -> "Destination is already referenced or duplicated"
                destinations.any { (id, path) -> key(files.first { it.id == id }.file) == key(path) } -> "Destination is unchanged"
                else -> null
            }
        val fingerprint = fingerprint(listOf(request.action, request.directory, snapshot, destinations, refs, shared, duplicate, collision))
        return BulkTarget(
            id,
            snapshot,
            fingerprint,
            destinations,
            reason == null,
            reason,
            if (reason == null &&
                request.action == BulkAction.DELETE
            ) {
                files.sumOf { it.size }
            } else {
                0
            },
        )
    }

    // Length-prefixed values avoid ambiguous delimiter-based serialization of user text.
    private fun fingerprint(value: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val output = java.io.DataOutputStream(java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest))

        fun encode(item: Any?) {
            when (item) {
                null -> output.writeByte(0)
                is Map<*, *> -> {
                    output.writeByte(1)
                    output.writeInt(item.size)
                    item.forEach { (k, v) ->
                        encode(k)
                        encode(v)
                    }
                }
                is Iterable<*> -> {
                    val items = item.toList()
                    output.writeByte(2)
                    output.writeInt(items.size)
                    items.forEach(::encode)
                }
                else ->
                    if (item::class.isData) {
                        output.writeByte(3)
                        item::class.members.filterIsInstance<kotlin.reflect.KProperty1<Any, *>>().sortedBy { it.name }.forEach {
                            encode(it.name)
                            encode(it.get(item))
                        }
                        output.writeByte(4)
                    } else {
                        output.writeByte(5)
                        val bytes = item.toString().toByteArray(Charsets.UTF_8)
                        output.writeInt(bytes.size)
                        output.write(bytes)
                    }
            }
        }
        encode(value)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun key(path: String) = path.replace('\\', '/').split('/').joinToString("/") { it.trimEnd(' ', '.').lowercase(Locale.ROOT) }

    private fun safePath(path: String): Boolean =
        path.replace('\\', '/').let { p ->
            !p.startsWith('/') &&
                p.split('/').size >= 4 &&
                p.split('/').none {
                    it.isBlank() ||
                        it == "." ||
                        it == ".." ||
                        it.endsWith('.') ||
                        it.endsWith(' ') ||
                        it.any { c -> c in ":?#%" || c.isISOControl() }
                }
        }
}
