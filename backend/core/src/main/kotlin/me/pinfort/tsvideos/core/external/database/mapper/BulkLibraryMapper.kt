package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.external.database.dto.CreatedFileDto
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select

@Mapper
interface BulkLibraryMapper {
    @Select("SELECT * FROM created_file ORDER BY id")
    fun allFiles(): List<CreatedFileDto>

    // Lock normalized aliases too: SMB is case-insensitive and accepts both separators.
    // The scan takes next-key locks, preventing insertion of new aliases during apply.
    @Select("SELECT * FROM created_file ORDER BY id FOR UPDATE")
    fun lockFiles(): List<CreatedFileDto>

    @Select("SELECT id FROM program WHERE id = #{id} FOR UPDATE")
    fun lockProgram(id: Long): Long?

    @Select("SELECT id FROM executed_file WHERE id = #{id} FOR UPDATE")
    fun lockExecuted(id: Long): Long?

    @Select("SELECT id FROM splitted_file WHERE executed_file_id = #{id} ORDER BY id FOR UPDATE")
    fun lockSplits(id: Long): List<Long>

    @Select("SELECT id FROM program WHERE executed_file_id = #{id} ORDER BY id FOR UPDATE")
    fun lockReferences(id: Long): List<Long>

    @Select("SELECT id FROM program WHERE executed_file_id = #{id} ORDER BY id")
    fun references(id: Long): List<Long>
}
