package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.FileFinding
import me.pinfort.tsvideos.core.domain.VerificationReport
import me.pinfort.tsvideos.core.domain.VerificationStatus
import me.pinfort.tsvideos.core.external.database.dto.CreatedFileDto
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import me.pinfort.tsvideos.storage.api.NasFileVerifier
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
        // Library runs read programs and files in two queries instead of one per program. Both are held in
        // memory, which is fine at the current library size (thousands of rows); stream them if that changes.
        // Like selectByExecutedFileId, the batch query only sees created_file rows that still have their
        // splitted_file, so an orphaned created_file is skipped by both paths.
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
                    NasFileVerifier.Result(NasFileVerifier.Status.UNCHECKED, reason = "NAS transfer not recorded as complete")
                } else {
                    nas.verify(file.file, file.size)
                }
            FileFinding(
                programId = programId,
                fileId = file.id,
                file = file.file,
                status = result.status.toVerificationStatus(),
                checkedAt = Instant.now(),
                expectedBytes = file.size,
                actualBytes = result.actualBytes,
                reason = result.reason,
            )
        }
    }

    internal companion object {
        /**
         * The library's status for a low-level storage check. Each storage status maps to the same-named value, so the
         * persisted `health_verification_finding.status`, API/CLI output and `tvmcli verify`'s exit code 4 are unchanged.
         * Exhaustive on purpose: a new storage status must be given a library meaning here.
         */
        fun NasFileVerifier.Status.toVerificationStatus(): VerificationStatus =
            when (this) {
                NasFileVerifier.Status.VERIFIED -> VerificationStatus.VERIFIED
                NasFileVerifier.Status.MISSING -> VerificationStatus.MISSING
                NasFileVerifier.Status.SIZE_MISMATCH -> VerificationStatus.SIZE_MISMATCH
                NasFileVerifier.Status.INACCESSIBLE -> VerificationStatus.INACCESSIBLE
                NasFileVerifier.Status.UNCHECKED -> VerificationStatus.UNCHECKED
            }
    }
}
