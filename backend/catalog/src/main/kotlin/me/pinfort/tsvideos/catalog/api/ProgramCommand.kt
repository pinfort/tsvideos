package me.pinfort.tsvideos.catalog.api

import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.ProgramDetail
import me.pinfort.tsvideos.catalog.domain.ProgramSearchPage
import me.pinfort.tsvideos.catalog.domain.ProgramSearchQuery
import me.pinfort.tsvideos.catalog.persistence.CreatedFileDto
import me.pinfort.tsvideos.catalog.persistence.CreatedFileMapper
import me.pinfort.tsvideos.catalog.persistence.GeneratedKeyHolder
import me.pinfort.tsvideos.catalog.persistence.ProgramDto
import me.pinfort.tsvideos.catalog.persistence.ProgramMapper
import me.pinfort.tsvideos.catalog.persistence.SplittedFileMapper
import org.slf4j.Logger
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.LocalDateTime

@Component
class ProgramCommand internal constructor(
    private val programMapper: ProgramMapper,
    private val createdFileMapper: CreatedFileMapper,
    private val executedFileCommand: ExecutedFileCommand,
    private val createdFileCommand: CreatedFileCommand,
    private val splittedFileMapper: SplittedFileMapper,
    private val logger: Logger,
    private val splittedFileCommand: SplittedFileCommand,
    private val directoryNameComponent: DirectoryNameComponent,
    private val executedFileTagCommand: ExecutedFileTagCommand,
    private val ledgerLocks: LedgerLocks,
    transactionManager: PlatformTransactionManager,
) {
    // Joins a caller's transaction (bulk apply); otherwise starts one whose first plain read follows every move lock.
    private val moveTransaction =
        TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ }

    // One REPEATABLE_READ snapshot keeps total consistent with the page under concurrent writes.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun search(query: ProgramSearchQuery): ProgramSearchPage =
        ProgramSearchPage(programMapper.search(query).map { it.toDomain() }, programMapper.countSearch(query), query.limit, query.offset)

    fun selectByName(
        name: String,
        limit: Int = 100,
        offset: Int = 0,
    ): List<Program> {
        val programs: List<ProgramDto> = programMapper.selectByName(name, limit, offset)

        return programs.map { it.toDomain() }
    }

    fun find(id: Long): Program? = programMapper.find(id)?.toDomain()

    fun findByName(name: String): Program? = programMapper.findByName(name)?.toDomain()

    fun findByExecutedFileId(executedFileId: Long): Program? = programMapper.findByExecutedFileId(executedFileId)?.toDomain()

    fun updateStatusByExecutedFileId(
        executedFileId: Long,
        status: Program.Status,
        dryRun: Boolean = false,
    ) {
        if (!dryRun) {
            programMapper.updateStatusByExecutedFileId(executedFileId, status.name)
        }
        logger.info("Update program status by executedFileId, executedFileId=$executedFileId, status=$status")
    }

    fun insert(
        name: String,
        executedFileId: Long,
        dryRun: Boolean = false,
    ): Program {
        val status = Program.Status.REGISTERED
        if (!dryRun) {
            val keyHolder = GeneratedKeyHolder()
            programMapper.insert(name, executedFileId, status.name, keyHolder)
            val program = find(keyHolder.id) ?: throw Exception("Program not found after insert, id=${keyHolder.id}")
            logger.info("Insert program, id=${program.id}, program=$program")
            return program
        }
        val program =
            Program(
                id = 0,
                name = name,
                executedFileId = executedFileId,
                status = status,
                drops = 0,
                size = 0,
                recordedAt = LocalDateTime.MIN,
                channel = "",
                title = "",
                channelName = "",
                duration = 0.0,
            )
        logger.info("Insert program, id=0, program=$program")
        return program
    }

    fun deleteByExecutedFileId(
        executedFileId: Long,
        dryRun: Boolean = false,
    ) {
        if (!dryRun) {
            programMapper.deleteByExecutedFileId(executedFileId)
        }
        logger.info("Delete program by executedFileId, executedFileId=$executedFileId")
    }

    fun videoFiles(program: Program): List<CreatedFile> =
        createdFileMapper.selectByExecutedFileId(program.executedFileId).map {
            it.toDomain()
        }

    fun hasTsFile(program: Program): Boolean =
        createdFileMapper.selectByExecutedFileId(program.executedFileId).any {
            it.toDomain().isTs
        }

    fun findDetail(id: Long): ProgramDetail? {
        val program: ProgramDto = programMapper.find(id) ?: return null
        val createdFiles: List<CreatedFileDto> = createdFileMapper.selectByExecutedFileId(program.executedFileId)
        return program.toProgramDetail(
            createdFiles,
            executedFileTagCommand.selectTags(program.executedFileId),
            executedFileTagCommand.selectChecks(program.executedFileId),
        )
    }

    @Transactional
    fun delete(
        program: Program,
        dryRun: Boolean = false,
    ) {
        val executedFile = executedFileCommand.find(program.executedFileId) ?: throw Exception("ExecutedFile not found")
        val splittedFiles = splittedFileMapper.selectByExecutedFileId(executedFile.id)
        val createdFiles: List<CreatedFileDto> = createdFileMapper.selectByExecutedFileId(program.executedFileId)

        splittedFiles.forEach {
            splittedFileCommand.delete(it.toDomain(), dryRun)
        }

        createdFiles.forEach {
            createdFileCommand.delete(it.toDomain(), dryRun)
        }

        executedFileCommand.delete(executedFile, dryRun)

        if (!dryRun) {
            programMapper.deleteById(program.id)
        }
        logger.info("Delete program, id=${program.id}, program=$program")
    }

    /**
     * Moves every created file of [program] into [newDirectory] on the NAS, then points the rows at the new paths.
     *
     * Protected before the first NAS move, in the bulk lock order (LedgerLocks): the guards of every current and
     * destination path and of the program's splits (which hold up new registrations on the program), the rows, then the
     * program graph. The files and splits are then read again under the locks, and the move is refused with
     * [IllegalStateException] before any change if they differ from the ones it planned. Call it outside a transaction
     * (the program read of [LedgerLocks.lockProgramGraph] then starts the snapshot, after every lock) or inside one that
     * took these locks before its first plain read, as a bulk apply does.
     */
    fun moveCreatedFiles(
        program: Program,
        newDirectory: String,
        dryRun: Boolean = false,
    ) {
        val planned: List<CreatedFileDto> = createdFileMapper.selectByExecutedFileId(program.executedFileId)
        if (dryRun) {
            moves(planned, newDirectory).forEach { (file, newFile) ->
                createdFileCommand.moveResource(file, newFile, true)
                createdFileCommand.recordMove(file, newFile, true)
            }
        } else {
            val plannedSplits = splitIds(program)
            moveTransaction.executeWithoutResult { moveLocked(program, newDirectory, planned, plannedSplits) }
        }
        logger.info("Move created files, id=${program.id}, newDirectory=$newDirectory, program=$program")
    }

    private fun moveLocked(
        program: Program,
        newDirectory: String,
        planned: List<CreatedFileDto>,
        plannedSplits: List<Long>,
    ) {
        val moves = moves(planned, newDirectory)
        ledgerLocks.lockCreatedFilePaths(moves.flatMap { (file, newFile) -> listOf(file.file, newFile) }, plannedSplits)
        ledgerLocks.lockCreatedFileRows(planned.map { it.id })
        val locked = ledgerLocks.lockProgramGraph(program.id)
        val current = createdFileMapper.selectByExecutedFileId(program.executedFileId)
        check(
            locked?.executedFileId == program.executedFileId &&
                current.sortedBy { it.id } == planned.sortedBy { it.id } &&
                splitIds(program) == plannedSplits,
        ) { "Created files of program ${program.id} changed before the move; nothing was moved" }

        // NAS moves cannot roll back with the transaction, so earlier files are moved back if a later one (or a row update)
        // fails. Every NAS move runs before the first row update (#112): an update of created_file.file runs the long
        // unique check on file, whose index locks may last until commit, so updating a row first could hold up unrelated
        // created_file inserts for the remaining transfers. The guards and row locks above already keep other writers off
        // these paths and rows.
        val moved = mutableListOf<Pair<String, String>>()
        try {
            moves.forEach { (file, newFile) ->
                createdFileCommand.moveResource(file, newFile)
                moved += file.file to newFile
            }
            moves.forEach { (file, newFile) -> createdFileCommand.recordMove(file, newFile) }
        } catch (e: Exception) {
            createdFileCommand.restoreMoved(moved)
            throw e
        }
    }

    private fun splitIds(program: Program): List<Long> =
        splittedFileMapper
            .selectByExecutedFileId(program.executedFileId)
            .map { it.id }
            .sorted()

    private fun moves(
        files: List<CreatedFileDto>,
        newDirectory: String,
    ): List<Pair<CreatedFile, String>> =
        files.map {
            val oldPath = Path.of(it.file.replace('\\', '/'))
            it.toDomain() to directoryNameComponent.replaceWithGivenDirectoryName(oldPath, newDirectory).toString().replace('/', '\\')
        }
}
