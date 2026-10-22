package me.pinfort.tsvideos.catalog.persistence

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
internal interface CreatedFileMapper {
    @Insert(
        """
            INSERT INTO created_file(splitted_file_id, file, size, mime, encoding, status, width, height)
            VALUES(#{splittedFileId}, #{file}, #{size}, #{mime}, #{encoding}, #{status}, #{width}, #{height})
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "keyHolder.id")
    fun insert(
        splittedFileId: Long,
        file: String,
        size: Long,
        mime: String?,
        encoding: String?,
        status: String,
        keyHolder: GeneratedKeyHolder,
        width: Int? = null,
        height: Int? = null,
    ): Int

    @Select(
        """
            SELECT
                id,
                splitted_file_id,
                file,
                size,
                mime,
                encoding,
                status,
                width,
                height
            FROM
                created_file
            WHERE
                id = #{id}
        """,
    )
    fun find(id: Long): CreatedFileDto?

    @Select(
        """
            SELECT
                id,
                splitted_file_id,
                file,
                size,
                mime,
                encoding,
                status,
                width,
                height
            FROM
                created_file
            WHERE
                splitted_file_id = #{splittedFileId}
        """,
    )
    fun selectBySplittedFileId(splittedFileId: Long): List<CreatedFileDto>

    @Select(
        """
            SELECT
                created_file.id,
                created_file.splitted_file_id,
                created_file.file,
                created_file.size,
                created_file.mime,
                created_file.encoding,
                created_file.status,
                created_file.width,
                created_file.height
            FROM
                created_file
            LEFT JOIN splitted_file
            ON created_file.splitted_file_id = splitted_file.id
            WHERE splitted_file.executed_file_id = #{executedFileId}
        """,
    )
    fun selectByExecutedFileId(executedFileId: Long): List<CreatedFileDto>

    @Select(
        """
            SELECT
                splitted_file.executed_file_id,
                created_file.id,
                created_file.splitted_file_id,
                created_file.file,
                created_file.size,
                created_file.mime,
                created_file.encoding,
                created_file.status,
                created_file.width,
                created_file.height
            FROM
                created_file
            INNER JOIN splitted_file
            ON created_file.splitted_file_id = splitted_file.id
            ORDER BY
                created_file.id
        """,
    )
    fun selectAllWithExecutedFileId(): List<ExecutedCreatedFileDto>

    @Select(
        """
        <script>
            SELECT
                splitted_file.executed_file_id,
                created_file.id,
                created_file.splitted_file_id,
                created_file.file,
                created_file.size,
                created_file.mime,
                created_file.encoding,
                created_file.status,
                created_file.width,
                created_file.height
            FROM
                created_file
            INNER JOIN splitted_file
            ON created_file.splitted_file_id = splitted_file.id
            WHERE
                created_file.status = 'FILE_MOVED'
                AND splitted_file.executed_file_id IN
                <foreach item="id" collection="executedFileIds" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY
                created_file.id
        </script>
        """,
    )
    fun selectMovedByExecutedFileIds(
        @Param("executedFileIds") executedFileIds: Collection<Long>,
    ): List<ExecutedCreatedFileDto>

    @Update(
        """
            UPDATE
                created_file
            SET
                file = #{file}
            WHERE
                id = #{createdFileId}
        """,
    )
    fun updateFile(
        createdFileId: Long,
        file: String,
    ): Int

    @Update(
        """
            UPDATE
                created_file
            SET
                width = #{width},
                height = #{height}
            WHERE
                id = #{id}
                AND width IS NULL
                AND height IS NULL
        """,
    )
    fun updateResolutionIfMissing(
        id: Long,
        width: Int,
        height: Int,
    ): Int

    @Update(
        """
            UPDATE
                created_file
            SET
                mime = #{mime}
            WHERE
                id = #{id}
                AND mime IS NULL
        """,
    )
    fun updateMimeIfMissing(
        id: Long,
        mime: String,
    ): Int

    @Update(
        """
            UPDATE
                created_file
            SET
                status = #{status}
            WHERE
                id = #{id}
        """,
    )
    fun updateStatus(
        id: Long,
        status: String,
    ): Int

    @Delete(
        """
            DELETE FROM
                created_file
            WHERE
                id = #{id}
        """,
    )
    fun delete(id: Long)
}
