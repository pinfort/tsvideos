package me.pinfort.tsvideos.core.external.database.mapper

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select

@Mapper
interface ExecutedFileTagMapper {
    @Insert(
        """
            INSERT IGNORE INTO executed_file_tag(executed_file_id, tag)
            VALUES(#{executedFileId}, #{tag})
        """,
    )
    fun insert(
        executedFileId: Long,
        tag: String,
    ): Int

    @Select(
        """
            SELECT
                tag
            FROM
                executed_file_tag
            WHERE
                executed_file_id = #{executedFileId}
            ORDER BY
                tag
        """,
    )
    fun selectByExecutedFileId(executedFileId: Long): List<String>

    @Delete(
        """
            DELETE FROM
                executed_file_tag
            WHERE
                executed_file_id = #{executedFileId}
        """,
    )
    fun deleteByExecutedFileId(executedFileId: Long): Int

    @Delete(
        """
            <script>
            DELETE FROM
                executed_file_tag
            WHERE
                executed_file_id = #{executedFileId}
                AND tag IN
                <foreach item="tag" collection="tags" open="(" separator="," close=")">
                    #{tag}
                </foreach>
            </script>
        """,
    )
    fun deleteByExecutedFileIdAndTags(
        executedFileId: Long,
        tags: Collection<String>,
    ): Int
}
