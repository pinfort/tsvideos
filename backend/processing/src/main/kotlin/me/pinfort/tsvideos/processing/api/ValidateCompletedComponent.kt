package me.pinfort.tsvideos.processing.api

import me.pinfort.tsvideos.catalog.api.CreatedFileCommand
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.api.SplittedFileCommand
import me.pinfort.tsvideos.storage.api.NasComponent
import org.slf4j.Logger
import org.springframework.stereotype.Component

/**
 * 番組の処理が完了しているかを判定する。
 * NAS 上に圧縮済みの TS ファイルとエンコード済みの動画ファイルが揃っていれば完了とみなす。
 */
@Component
class ValidateCompletedComponent(
    private val programCommand: ProgramCommand,
    private val splittedFileCommand: SplittedFileCommand,
    private val createdFileCommand: CreatedFileCommand,
    private val nasComponent: NasComponent,
    private val logger: Logger,
) {
    fun validate(programId: Long): Boolean {
        val program = programCommand.find(programId)
        if (program == null) {
            logger.info("Program not found, programId=$programId")
            return false
        }

        val splittedFiles = splittedFileCommand.selectByExecutedFileId(program.executedFileId)
        if (splittedFiles.isEmpty()) {
            logger.info("Splitted file not found, executedFileId=${program.executedFileId}, programId=${program.id}")
            return false
        }

        val existingCreatedFiles =
            splittedFiles
                .flatMap { createdFileCommand.selectBySplittedFileId(it.id) }
                .filter { nasComponent.resourceExists(it.file) }
        logger.info("Created files existing on NAS, programId=${program.id}, createdFiles=$existingCreatedFiles")

        val gzipFileExists = existingCreatedFiles.any { it.encoding == "gzip" && it.isTs }
        val movieFileExists = existingCreatedFiles.any { it.isMp4 }

        if (gzipFileExists && movieFileExists) {
            logger.info("Program valid, programId=${program.id}")
            return true
        }
        logger.info(
            "Program invalid, programId=${program.id}, name=${program.name}, " +
                "gzipFileExists=$gzipFileExists, movieFileExists=$movieFileExists",
        )
        return false
    }
}
