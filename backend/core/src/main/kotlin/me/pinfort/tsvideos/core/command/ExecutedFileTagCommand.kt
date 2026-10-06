package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.external.database.mapper.ExecutedFileCheckMapper
import me.pinfort.tsvideos.core.external.database.mapper.ExecutedFileTagMapper
import org.slf4j.Logger
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 録画のタグ (executed_file_tag) と、実行した検出処理の記録 (executed_file_check) を扱う。
 * タグ名・検出処理名は [me.pinfort.tsvideos.core.domain.ExecutedFileTag] /
 * [me.pinfort.tsvideos.core.domain.ExecutedFileCheck] を参照。
 */
@Component
class ExecutedFileTagCommand(
    private val executedFileTagMapper: ExecutedFileTagMapper,
    private val executedFileCheckMapper: ExecutedFileCheckMapper,
    private val logger: Logger,
) {
    fun selectTags(executedFileId: Long): List<String> = executedFileTagMapper.selectByExecutedFileId(executedFileId)

    fun selectChecks(executedFileId: Long): List<String> = executedFileCheckMapper.selectByExecutedFileId(executedFileId)

    /** 検出処理 [checker] を実行したことと、それが見つけた [tags] を記録する */
    @Transactional
    fun recordCheck(
        executedFileId: Long,
        checker: String,
        tags: Set<String>,
        dryRun: Boolean = false,
    ) {
        if (!dryRun) {
            tags.forEach { executedFileTagMapper.insert(executedFileId, it) }
            executedFileCheckMapper.upsert(executedFileId, checker, LocalDateTime.now())
        }
        logger.info("Record check, executedFileId=$executedFileId, checker=$checker, tags=$tags")
    }

    fun deleteByExecutedFileId(
        executedFileId: Long,
        dryRun: Boolean = false,
    ) {
        if (!dryRun) {
            executedFileTagMapper.deleteByExecutedFileId(executedFileId)
            executedFileCheckMapper.deleteByExecutedFileId(executedFileId)
        }
        logger.info("Delete tags and checks, executedFileId=$executedFileId")
    }
}
