package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.FileFinding
import me.pinfort.tsvideos.core.domain.VerificationReport
import me.pinfort.tsvideos.core.domain.VerificationStatus
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
    /** Null means the explicitly requested program does not exist; an empty library is a valid report. */
    fun verify(programId: Long? = null): VerificationReport? {
        require(programId == null || programId > 0) { "Program ID must be positive" }
        val programs =
            if (programId == null) {
                programMapper.selectAllIds().mapNotNull { programMapper.find(it) }
            } else {
                listOf(programMapper.find(programId) ?: return null)
            }
        return VerificationReport(
            programs.flatMap { program ->
                val files = createdFileMapper.selectByExecutedFileId(program.executedFileId).sortedBy { it.id }
                if (files.isEmpty()) {
                    listOf(
                        FileFinding(
                            program.id,
                            null,
                            null,
                            VerificationStatus.UNCHECKED,
                            Instant.now(),
                            null,
                            null,
                            "No NAS artifacts registered",
                        ),
                    )
                } else {
                    files.map { dto ->
                        val file = dto.toDomain()
                        val result =
                            if (file.status != CreatedFile.Status.FILE_MOVED) {
                                NasFileVerifier.Result(VerificationStatus.UNCHECKED, reason = "NAS transfer not recorded as complete")
                            } else {
                                nasFileVerifier.verify(file.file, file.size)
                            }
                        FileFinding(
                            program.id,
                            file.id,
                            file.file,
                            result.status,
                            Instant.now(),
                            file.size,
                            result.actualBytes,
                            result.reason,
                        )
                    }
                }
            },
        )
    }
}
