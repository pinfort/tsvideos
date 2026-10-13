package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.domain.LibraryStatsQuery
import me.pinfort.tsvideos.core.external.database.dto.StatsFileDto
import me.pinfort.tsvideos.core.external.database.dto.StatsProgramDto
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select

@Mapper
interface LibraryStatsMapper {
    @Select(
        """
        <script>
        SELECT p.id, p.executed_file_id, p.status, e.drops, e.channel, e.recorded_at
        FROM program p LEFT JOIN executed_file e ON e.id = p.executed_file_id
        <where>
          <if test="recordedFrom != null">e.recorded_at &gt;= #{recordedFrom}</if>
          <if test="recordedTo != null">AND e.recorded_at &lt; #{recordedTo}</if>
        </where>
        ORDER BY p.id
        </script>
    """,
    )
    fun programs(query: LibraryStatsQuery): List<StatsProgramDto>

    @Select(
        """
        <script>
        SELECT c.id, s.executed_file_id, c.size, c.mime, c.encoding, c.status
        FROM created_file c JOIN splitted_file s ON s.id = c.splitted_file_id
        WHERE EXISTS (
          SELECT 1 FROM program p LEFT JOIN executed_file e ON e.id = p.executed_file_id
          WHERE p.executed_file_id = s.executed_file_id
          <if test="recordedFrom != null">AND e.recorded_at &gt;= #{recordedFrom}</if>
          <if test="recordedTo != null">AND e.recorded_at &lt; #{recordedTo}</if>
        )
        ORDER BY c.id
        </script>
    """,
    )
    fun files(query: LibraryStatsQuery): List<StatsFileDto>
}
