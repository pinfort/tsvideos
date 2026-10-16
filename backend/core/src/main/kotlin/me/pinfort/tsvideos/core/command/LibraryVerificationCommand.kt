package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.FileFinding
import me.pinfort.tsvideos.core.domain.VerificationReport
import me.pinfort.tsvideos.core.domain.VerificationStatus
import me.pinfort.tsvideos.core.external.database.dto.CreatedFileDto
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import me.pinfort.tsvideos.core.external.samba.NasFileVerifier
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class LibraryVerificationCommand(
    private val programMapper: ProgramMapper,
    private val createdFileMapper: CreatedFileMapper,
    private val nasFileVerifier: NasFileVerifier,
) {
    /**
     * Null means the explicitly requested program does not exist; an empty library is a valid report.
     * [onProgress] receives (programs checked, total programs) after each program.
     */
    fun verify(
        programId: Long? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): VerificationReport? {
        require(programId == null || programId > 0) { "Program ID must be positive" }
        // Library runs read programs and files in two queries instead of one per program.
        val programs: List<Pair<Long, Long>>
        val filesByExecutedFileId: Map<Long, List<CreatedFileDto>>
        if (programId == null) {
            programs = programMapper.selectAllOrderById().map { it.id to it.executedFileId }
            filesByExecutedFileId =
                createdFileMapper.selectAllWithExecutedFileId().groupBy({ it.executedFileId }, { it.toCreatedFileDto() })
        } else {
            val program = programMapper.find(programId) ?: return null
            programs = listOf(program.id to program.executedFileId)
            filesByExecutedFileId =
                mapOf(program.executedFileId to createdFileMapper.selectByExecutedFileId(program.executedFileId).sortedBy { it.id })
        }
        val findings =
            nasFileVerifier.session { nas ->
                programs.flatMapIndexed { index, (id, executedFileId) ->
                    verifyProgram(nas, id, filesByExecutedFileId[executedFileId].orEmpty())
                        .also { onProgress(index + 1, programs.size) }
                }
            }
        return VerificationReport(findings)
    }

    private fun verifyProgram(
        nas: NasFileVerifier.Session,
        programId: Long,
        files: List<CreatedFileDto>,
    ): List<FileFinding> {
        if (files.isEmpty()) {
            return listOf(
                FileFinding(
                    programId = programId,
                    fileId = null,
                    file = null,
                    status = VerificationStatus.UNCHECKED,
                    checkedAt = Instant.now(),
                    expectedBytes = null,
                    actualBytes = null,
                    reason = "No NAS artifacts registered",
                ),
            )
        }
        return files.map { dto ->
            val file = dto.toDomain()
            val result =
                if (file.status != CreatedFile.Status.FILE_MOVED) {
                    NasFileVerifier.Result(VerificationStatus.UNCHECKED, reason = "NAS transfer not recorded as complete")
                } else {
                    nas.verify(file.file, file.size)
                }
            FileFinding(
                programId = programId,
                fileId = file.id,
                file = file.file,
                status = result.status,
                checkedAt = Instant.now(),
                expectedBytes = file.size,
                actualBytes = result.actualBytes,
                reason = result.reason,
            )
        }
    }
}
