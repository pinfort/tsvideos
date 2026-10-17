package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.external.database.dto.CreatedFileDto
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

@Mapper
interface BulkLibraryMapper {
    @Select("SELECT id FROM program ORDER BY id FOR UPDATE")
    fun lockAllPrograms(): List<Long>

    @Select("SELECT id FROM executed_file ORDER BY id FOR UPDATE")
    fun lockAllExecuted(): List<Long>

    @Select("SELECT id FROM splitted_file ORDER BY id FOR UPDATE")
    fun lockAllSplits(): List<Long>

    @Select("SELECT * FROM created_file ORDER BY id")
    fun allFiles(): List<CreatedFileDto>

    // Lock normalized aliases too: SMB is case-insensitive and accepts both separators.
    // The scan takes next-key locks, preventing insertion of new aliases during apply.
    // Every bulk lock uses the same bounded (MariaDB-only) wait, so a busy library fails this target fast
    // instead of queueing behind the pipeline.
    @Select("SET STATEMENT innodb_lock_wait_timeout = 10 FOR SELECT * FROM created_file ORDER BY id FOR UPDATE")
    fun lockFiles(): List<CreatedFileDto>

    // Every created file of the given programs, one row per (program, file) so shared executed records count twice.
    @Select(
        """
        <script>
        SELECT created_file.*
        FROM program
        INNER JOIN splitted_file ON splitted_file.executed_file_id = program.executed_file_id
        INNER JOIN created_file ON created_file.splitted_file_id = splitted_file.id
        WHERE program.id IN
        <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY program.id, created_file.id
        </script>
        """,
    )
    fun createdFilesOfPrograms(
        @Param("ids") ids: List<Long>,
    ): List<CreatedFileDto>

    @Select("SET STATEMENT innodb_lock_wait_timeout = 10 FOR SELECT id FROM program WHERE id = #{id} FOR UPDATE")
    fun lockProgram(id: Long): Long?

    @Select("SET STATEMENT innodb_lock_wait_timeout = 10 FOR SELECT id FROM executed_file WHERE id = #{id} FOR UPDATE")
    fun lockExecuted(id: Long): Long?

    @Select(
        "SET STATEMENT innodb_lock_wait_timeout = 10 FOR SELECT id FROM splitted_file WHERE executed_file_id = #{id} ORDER BY id FOR UPDATE",
    )
    fun lockSplits(id: Long): List<Long>

    @Select("SET STATEMENT innodb_lock_wait_timeout = 10 FOR SELECT id FROM program WHERE executed_file_id = #{id} ORDER BY id FOR UPDATE")
    fun lockReferences(id: Long): List<Long>

    @Select("SELECT id FROM program WHERE executed_file_id = #{id} ORDER BY id")
    fun references(id: Long): List<Long>
}
