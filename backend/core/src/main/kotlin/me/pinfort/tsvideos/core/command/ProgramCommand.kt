package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.component.DirectoryNameComponent
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramDetail
import me.pinfort.tsvideos.core.external.database.dto.CreatedFileDto
import me.pinfort.tsvideos.core.external.database.dto.ProgramDto
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.GeneratedKeyHolder
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import me.pinfort.tsvideos.core.external.database.mapper.SplittedFileMapper
import org.slf4j.Logger
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Path
import java.time.LocalDateTime

@Component
class ProgramCommand(
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

        createdFiles.forEach {
            val oldPath = Path.of(it.file.replace('\\', '/'))
            val newPath = directoryNameComponent.replaceWithGivenDirectoryName(oldPath, newDirectory)
            createdFileCommand.move(it.toDomain(), newPath.toString().replace('/', '\\'), dryRun)
        }

        logger.info("Move created files, id=${program.id}, newDirectory=$newDirectory, program=$program")
    }
}
