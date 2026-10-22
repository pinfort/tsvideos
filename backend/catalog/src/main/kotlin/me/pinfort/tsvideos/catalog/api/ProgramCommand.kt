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
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
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
) {
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

    fun hasTsFile(program: Program): Boolean {
        createdFileMapper.selectByExecutedFileId(program.executedFileId).forEach {
            if (it.toDomain().isTs) {
                return true
            }
        }
        return false
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

    @Transactional
    fun moveCreatedFiles(
        program: Program,
        newDirectory: String,
        dryRun: Boolean = false,
    ) {
        val createdFiles: List<CreatedFileDto> = createdFileMapper.selectByExecutedFileId(program.executedFileId)

        // NAS moves cannot roll back with the transaction, so earlier files are moved back if a later one fails.
        val moved = mutableListOf<Pair<String, String>>()
        try {
            createdFiles.forEach {
                val oldPath = Path.of(it.file.replace('\\', '/'))
                val newFile = directoryNameComponent.replaceWithGivenDirectoryName(oldPath, newDirectory).toString().replace('/', '\\')
                createdFileCommand.move(it.toDomain(), newFile, dryRun)
                if (!dryRun) moved += it.file to newFile
            }
        } catch (e: Exception) {
            createdFileCommand.restoreMoved(moved)
            throw e
        }

        logger.info("Move created files, id=${program.id}, newDirectory=$newDirectory, program=$program")
    }
}
