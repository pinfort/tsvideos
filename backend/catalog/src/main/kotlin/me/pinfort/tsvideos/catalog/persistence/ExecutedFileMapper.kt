package me.pinfort.tsvideos.catalog.persistence

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import java.time.LocalDateTime

@Mapper
internal interface ExecutedFileMapper {
    @Insert(
        """
            INSERT INTO executed_file(file, drops, size, recorded_at, channel, title, channelName, duration, status)
            VALUES(#{file}, #{drops}, #{size}, #{recordedAt}, #{channel}, #{title}, #{channelName}, #{duration}, #{status})
        """,
    )
    @Options(useGeneratedKeys = true, keyProperty = "keyHolder.id")
    fun insert(
        file: String,
        drops: Int,
        size: Long,
        recordedAt: LocalDateTime,
        channel: String,
        title: String,
        channelName: String,
        duration: Double,
        status: String,
        keyHolder: GeneratedKeyHolder,
    ): Int

    @Update(
        """
            UPDATE
                executed_file
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

    @Select(
        """
            SELECT
                id,
                file,
                drops,
                size,
                recorded_at,
                channel,
                title,
                channelName,
                duration,
                status
            FROM
                executed_file
            WHERE
                id = #{id}
        """,
    )
    fun find(id: Long): ExecutedFileDto?

    @Delete(
        """
            DELETE FROM
                executed_file
            WHERE
                id = #{id}
        """,
    )
    fun delete(id: Long)

    @Select(
        """
            SELECT
                id,
                file,
                drops,
                size,
                recorded_at,
                channel,
                title,
                channelName,
                duration,
                status
            FROM
                executed_file
            WHERE
                file = #{file}
        """,
    )
    fun selectByFile(file: String): List<ExecutedFileDto>
}
