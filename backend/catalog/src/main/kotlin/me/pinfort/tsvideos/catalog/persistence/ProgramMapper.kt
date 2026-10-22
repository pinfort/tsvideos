package me.pinfort.tsvideos.catalog.persistence

import me.pinfort.tsvideos.catalog.api.ProgramSummary
import me.pinfort.tsvideos.catalog.domain.ProgramSearchQuery
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.SelectProvider
import org.apache.ibatis.annotations.Update

@Mapper
internal interface ProgramMapper {
    /** Unlike [selectAll], keeps programs whose executed_file row is gone. */
    @Select(
        """
            SELECT
                pg.id,
                pg.name,
                pg.executed_file_id,
                pg.status,
                ex.drops,
                ex.size,
                ex.recorded_at,
                ex.channel,
                ex.title,
                ex.channelName,
                ex.duration
            FROM
                program pg
                LEFT OUTER JOIN executed_file ex
                    ON pg.executed_file_id = ex.id
            ORDER BY
                pg.id
        """,
    )
    fun selectAllOrderById(): List<ProgramDto>

    @Select("SELECT id FROM program ORDER BY id")
    fun selectAllIds(): List<Long>

    @Select("SELECT id FROM program WHERE id=#{id}")
    fun findId(
        @Param("id") id: Long,
    ): Long?

    @Select(
        """
        <script>
        SELECT p.id, p.name, p.status, e.drops FROM program p LEFT JOIN executed_file e ON e.id=p.executed_file_id
        WHERE p.id IN <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach> ORDER BY p.id
        </script>
    """,
    )
    fun summariesByIds(
        @Param("ids") ids: List<Long>,
    ): List<ProgramSummary>

    @Select(
        "SELECT p.id, p.name, p.status, e.drops FROM program p LEFT JOIN executed_file e ON e.id=p.executed_file_id WHERE p.status=#{status} ORDER BY p.id",
    )
    fun summariesByStatus(
        @Param("status") status: String,
    ): List<ProgramSummary>

    @Select(
        "SELECT p.id, p.name, p.status, e.drops FROM program p JOIN executed_file e ON e.id=p.executed_file_id WHERE e.drops>0 ORDER BY p.id",
    )
    fun summariesWithDrops(): List<ProgramSummary>

    @SelectProvider(type = ProgramSearchSql::class, method = "search")
    fun search(query: ProgramSearchQuery): List<ProgramDto>

    @SelectProvider(type = ProgramSearchSql::class, method = "count")
    fun countSearch(query: ProgramSearchQuery): Long

    @Insert(
        """
            INSERT INTO program(name, executed_file_id, status)
            VALUES(#{name}, #{executedFileId}, #{status})
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "keyHolder.id")
    fun insert(
        name: String,
        executedFileId: Long,
        status: String,
        keyHolder: GeneratedKeyHolder,
    ): Int

    @Select(
        """
            SELECT
                pg.id,
                pg.name,
                pg.executed_file_id,
                pg.status,
                ex.drops,
                ex.size,
                ex.recorded_at,
                ex.channel,
                ex.title,
                ex.channelName,
                ex.duration
            FROM
                program pg
                LEFT OUTER JOIN executed_file ex
                    ON pg.executed_file_id = ex.id
            WHERE
                pg.name = #{name}
        """,
    )
    fun findByName(name: String): ProgramDto?

    @Delete(
        """
            DELETE FROM
                program
            WHERE
                executed_file_id = #{executedFileId}
        """,
    )
    fun deleteByExecutedFileId(executedFileId: Long): Int

    @Select(
        """
        SELECT
            pg.id,
            pg.name,
            pg.executed_file_id,
            pg.status,
            ex.drops,
            ex.size,
            ex.recorded_at,
            ex.channel,
            ex.title,
            ex.channelName,
            ex.duration
        FROM
            program pg
            LEFT OUTER JOIN executed_file ex
                ON pg.executed_file_id = ex.id
        WHERE
            pg.name COLLATE utf8mb4_unicode_ci LIKE CONCAT('%', #{name}, '%')
        LIMIT
            #{limit}
        OFFSET
            #{offset}
    """,
    )
    fun selectByName(
        name: String,
        limit: Int = 100,
        offset: Int = 0,
    ): List<ProgramDto>

    @Select(
        """
            SELECT
                pg.id,
                pg.name,
                pg.executed_file_id,
                pg.status,
                ex.drops,
                ex.size,
                ex.recorded_at,
                ex.channel,
                ex.title,
                ex.channelName,
                ex.duration
            FROM
                program pg
                LEFT OUTER JOIN executed_file ex
                    ON pg.executed_file_id = ex.id
            WHERE
                pg.id = #{id}
        """,
    )
    fun find(id: Long): ProgramDto?

    @Delete(
        """
            DELETE FROM
                program
            WHERE
                id = #{id}
        """,
    )
    fun deleteById(id: Long)

    @Select(
        """
            SELECT
                pg.id,
                pg.name,
                pg.executed_file_id,
                pg.status,
                ex.drops,
                ex.size,
                ex.recorded_at,
                ex.channel,
                ex.title,
                ex.channelName,
                ex.duration
            FROM
                program pg
                LEFT OUTER JOIN executed_file ex
                    ON pg.executed_file_id = ex.id
            WHERE
                pg.executed_file_id = #{executedFileId}
        """,
    )
    fun findByExecutedFileId(executedFileId: Long): ProgramDto?

    @Select(
        """
            SELECT
                pg.id,
                pg.name,
                pg.executed_file_id,
                pg.status,
                ex.drops,
                ex.size,
                ex.recorded_at,
                ex.channel,
                ex.title,
                ex.channelName,
                ex.duration
            FROM
                program pg
                INNER JOIN executed_file ex
                    ON pg.executed_file_id = ex.id
        """,
    )
    fun selectAll(): List<ProgramDto>

    @Update(
        """
            UPDATE
                program
            SET
                status = #{status}
            WHERE
                executed_file_id = #{executedFileId}
        """,
    )
    fun updateStatusByExecutedFileId(
        executedFileId: Long,
        status: String,
    ): Int
}
