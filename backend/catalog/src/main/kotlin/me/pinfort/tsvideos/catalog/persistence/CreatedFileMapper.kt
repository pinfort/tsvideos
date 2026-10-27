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
    // Claims the guard of pathKey (a path or split key, migration 076), creating its row on first use; held until the
    // transaction ends. A primary-key upsert locks that one record (no gap lock), so writers of other keys never wait.
    // A writer of the same key waits up to the server's innodb_lock_wait_timeout (not unbounded); bulk operations take
    // the same guard through BulkLibraryMapper with a 10-second override instead.
    @Insert("INSERT INTO created_file_path_guard(path_key) VALUES(#{pathKey}) ON DUPLICATE KEY UPDATE path_key = path_key")
    fun lockPathGuard(
        @Param("pathKey") pathKey: ByteArray,
    ): Int

    // Shared lock on the split a registration attaches to (created_file has no foreign key to splitted_file): a locking
    // read, so it sees the latest committed state and finds nothing once a delete of the split has committed.
    @Select("SELECT id FROM splitted_file WHERE id = #{splittedFileId} LOCK IN SHARE MODE")
    fun lockSplitShared(
        @Param("splittedFileId") splittedFileId: Long,
    ): Long?

    @Insert(
        """
            INSERT INTO created_file(splitted_file_id, file, path_key, size, mime, encoding, status, width, height)
            VALUES(#{splittedFileId}, #{file}, #{pathKey}, #{size}, #{mime}, #{encoding}, #{status}, #{width}, #{height})
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "keyHolder.id")
    fun insert(
        splittedFileId: Long,
        file: String,
        pathKey: ByteArray,
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
                file = #{file},
                path_key = #{pathKey}
            WHERE
                id = #{createdFileId}
        """,
    )
    fun updateFile(
        createdFileId: Long,
        file: String,
        pathKey: ByteArray,
    ): Int

    // Rows without a stored path_key (registered before migration 076 or by SQL outside catalog), in id order after afterId.
    @Select(
        """
            SELECT id, splitted_file_id, file, size, mime, encoding, status, width, height
            FROM created_file
            WHERE path_key IS NULL AND id > #{afterId}
            ORDER BY id
            LIMIT #{limit}
        """,
    )
    fun selectWithoutPathKey(
        afterId: Long,
        limit: Int,
    ): List<CreatedFileDto>

    // Row locks on the ids nobody else holds (primary-key equality, record locks only); a row locked by a running move or
    // bulk apply is skipped instead of waited for. ids must be non-empty.
    @Select(
        """
        <script>
        SELECT id FROM created_file WHERE id IN
        <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY id FOR UPDATE SKIP LOCKED
        </script>
        """,
    )
    fun lockUnlockedRows(
        @Param("ids") ids: List<Long>,
    ): List<Long>

    // Only while the row still has no key and the same file, so a concurrent move (which writes its own key) wins.
    @Update("UPDATE created_file SET path_key = #{pathKey} WHERE id = #{id} AND file = #{file} AND path_key IS NULL")
    fun fillPathKey(
        id: Long,
        file: String,
        pathKey: ByteArray,
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
