package me.pinfort.tsvideos.catalog.persistence

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
internal interface SplittedFileMapper {
    @Insert(
        """
        INSERT INTO splitted_file(executed_file_id, file, size, duration, status)
        VALUES(#{executedFileId}, #{file}, #{size}, #{duration}, #{status})
    """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "keyHolder.id")
    fun insert(
        executedFileId: Long,
        file: String,
        size: Long,
        duration: Double,
        status: String,
        keyHolder: GeneratedKeyHolder,
    ): Int

    @Update(
        """
        UPDATE splitted_file SET status = #{status} WHERE id = #{id}
    """,
    )
    fun updateStatus(
        id: Long,
        status: String,
    ): Int

    @Select(
        """
        SELECT
            id,
            executed_file_id,
            file,
            size,
            duration,
            status
        FROM
            splitted_file
        WHERE
            executed_file_id = #{executedFileId}
    """,
    )
    fun selectByExecutedFileId(executedFileId: Long): List<SplittedFileDto>

    @Select(
        """
        SELECT
            id,
            executed_file_id,
            file,
            size,
            duration,
            status
        FROM
            splitted_file
        WHERE
            file = #{file}
    """,
    )
    fun selectByFile(file: String): List<SplittedFileDto>

    @Delete(
        """
        DELETE FROM
            splitted_file
        WHERE
            id = #{id}
    """,
    )
    fun delete(id: Long)
}
