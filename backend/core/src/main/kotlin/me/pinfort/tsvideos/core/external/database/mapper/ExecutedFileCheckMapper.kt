package me.pinfort.tsvideos.core.external.database.mapper

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import java.time.LocalDateTime

@Mapper
interface ExecutedFileCheckMapper {
    @Insert(
        """
            INSERT INTO executed_file_check(executed_file_id, checker, checked_at)
            VALUES(#{executedFileId}, #{checker}, #{checkedAt})
            ON DUPLICATE KEY UPDATE
                checked_at = VALUES(checked_at)
        """,
    )
    fun upsert(
        executedFileId: Long,
        checker: String,
        checkedAt: LocalDateTime,
    ): Int

    @Select(
        """
            SELECT
                checker
            FROM
                executed_file_check
            WHERE
                executed_file_id = #{executedFileId}
            ORDER BY
                checker
        """,
    )
    fun selectByExecutedFileId(executedFileId: Long): List<String>

    @Delete(
        """
            DELETE FROM
                executed_file_check
            WHERE
                executed_file_id = #{executedFileId}
        """,
    )
    fun deleteByExecutedFileId(executedFileId: Long): Int
}
