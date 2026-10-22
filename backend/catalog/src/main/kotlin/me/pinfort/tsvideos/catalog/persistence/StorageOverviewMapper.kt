package me.pinfort.tsvideos.catalog.persistence

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select

@Mapper
internal interface StorageOverviewMapper {
    @Select(
        """
        SELECT p.id AS program_id, p.name AS program_name, c.id AS file_id, c.file, c.size
        FROM program p
        LEFT JOIN splitted_file s ON s.executed_file_id = p.executed_file_id
        LEFT JOIN created_file c ON c.splitted_file_id = s.id
        ORDER BY p.id, c.id
    """,
    )
    fun references(): List<StorageReferenceDto>
}
