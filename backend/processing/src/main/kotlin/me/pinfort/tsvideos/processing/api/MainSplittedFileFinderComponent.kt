package me.pinfort.tsvideos.processing.api

import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.processing.domain.TsVideosException
import org.springframework.stereotype.Component

@Component
class MainSplittedFileFinderComponent {
    fun find(
        executedFile: ExecutedFile,
        splittedFiles: List<SplittedFile>,
    ): SplittedFile {
        val mainFile =
            when (splittedFiles.size) {
                1 -> splittedFiles[0]
                2 -> findFromTwo(splittedFiles)
                else -> throw TsVideosException("unexpected splitted file count! count=${splittedFiles.size}")
            }
        validate(executedFile, mainFile)
        return mainFile
    }

    private fun findFromTwo(splittedFiles: List<SplittedFile>): SplittedFile {
        val (mainFile, garbageFile) =
            if (splittedFiles[0].size >= splittedFiles[1].size) {
                splittedFiles[0] to splittedFiles[1]
            } else {
                splittedFiles[1] to splittedFiles[0]
            }
        if (garbageFile.duration > 20.0) {
            throw TsVideosException("garbage file duration is too long! duration=${garbageFile.duration}")
        }
        if (garbageFile.size > mainFile.size * 0.1) {
            throw TsVideosException("garbage file size is too large! garbageSize=${garbageFile.size}, mainSize=${mainFile.size}")
        }
        return mainFile
    }

    private fun validate(
        executedFile: ExecutedFile,
        mainFile: SplittedFile,
    ) {
        if (mainFile.duration < 1) {
            throw TsVideosException("main file duration is too short! duration=${mainFile.duration}")
        }
        if (executedFile.drops > 1000) {
            throw TsVideosException("too many drops! drops=${executedFile.drops}")
        }
        val diff = executedFile.duration.toInt() - mainFile.duration.toInt()
        if (diff < -5 || diff > 20) {
            throw TsVideosException(
                "main file duration is too different from the original! " +
                    "originalDuration=${executedFile.duration}, mainFileDuration=${mainFile.duration}",
            )
        }
    }
}
