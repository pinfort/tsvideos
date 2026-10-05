package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.external.database.dto.ProgramDto
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface ProgramMapper {
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
