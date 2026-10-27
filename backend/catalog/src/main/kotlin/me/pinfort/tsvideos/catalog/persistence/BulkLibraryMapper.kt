package me.pinfort.tsvideos.catalog.persistence

import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

/** Catalog-owned ledger locks and bulk snapshot reads; callers use them through catalog's LedgerLocks / LedgerSnapshots. */
@Mapper
internal interface BulkLibraryMapper {
    // Guarded (cleanup) apply only, after lockPathGuard and lockFiles: created_file -> program -> executed_file ->
    // splitted_file is the same table order as the per-target locks below, and nothing else in the app takes row locks
    // on these tables. These lock whole tables: operator CLI only, never from a web request path.
    @Select("SET STATEMENT innodb_lock_wait_timeout = 10 FOR SELECT id FROM program ORDER BY id FOR UPDATE")
    fun lockAllPrograms(): List<Long>

    @Select("SET STATEMENT innodb_lock_wait_timeout = 10 FOR SELECT id FROM executed_file ORDER BY id FOR UPDATE")
    fun lockAllExecuted(): List<Long>

    @Select("SET STATEMENT innodb_lock_wait_timeout = 10 FOR SELECT id FROM splitted_file ORDER BY id FOR UPDATE")
    fun lockAllSplits(): List<Long>

    // Explicit columns throughout: CreatedFileDto is mapped by column order, so a new created_file column (such as
    // chapters_probed) must not shift it.
    @Select("SELECT id, splitted_file_id, file, size, mime, encoding, status, width, height FROM created_file ORDER BY id")
    fun allFiles(): List<CreatedFileDto>

    // Guarded (cleanup) apply only: the full scan's next-key locks freeze the whole table, so no created_file row can be
    // inserted while it is held. Every bulk lock uses the same bounded (MariaDB-only) wait, so a busy library fails
    // this target fast instead of queueing behind the pipeline.
    @Select(
        "SET STATEMENT innodb_lock_wait_timeout = 10 FOR " +
            "SELECT id, splitted_file_id, file, size, mime, encoding, status, width, height FROM created_file ORDER BY id FOR UPDATE",
    )
    fun lockFiles(): List<CreatedFileDto>

    // The bounded variant of CreatedFileMapper.lockPathGuard: a record lock on one created_file_path_guard key, never
    // a gap lock, so writers of other paths do not wait (#112).
    @Insert(
        "SET STATEMENT innodb_lock_wait_timeout = 10 FOR " +
            "INSERT INTO created_file_path_guard(path_key) VALUES(#{pathKey}) ON DUPLICATE KEY UPDATE path_key = path_key",
    )
    fun lockPathGuard(
        @Param("pathKey") pathKey: ByteArray,
    ): Int

    // Primary-key equality only, so existing rows get record locks; ids must be non-empty.
    @Select(
        """
        <script>
        SET STATEMENT innodb_lock_wait_timeout = 10 FOR
        SELECT id FROM created_file WHERE id IN
        <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY id FOR UPDATE
        </script>
        """,
    )
    fun lockFileRows(
        @Param("ids") ids: List<Long>,
    ): List<Long>

    // Candidates for a normalized path lookup by the path_key index; keys must be non-empty.
    @Select(
        """
        <script>
        SELECT id, splitted_file_id, file, size, mime, encoding, status, width, height FROM created_file
        WHERE path_key IN
        <foreach item="key" collection="keys" open="(" separator="," close=")">#{key}</foreach>
        ORDER BY id
        </script>
        """,
    )
    fun filesWithPathKeys(
        @Param("keys") keys: List<ByteArray>,
    ): List<CreatedFileDto>

    // Rows not keyed yet (see CreatedFileCatalog.fillMissingPathKeys); empty once they are filled in.
    @Select(
        "SELECT id, splitted_file_id, file, size, mime, encoding, status, width, height FROM created_file " +
            "WHERE path_key IS NULL ORDER BY id",
    )
    fun filesWithoutPathKey(): List<CreatedFileDto>

    // Every created file of the given programs, one row per (program, file) so shared executed records count twice.
    @Select(
        """
        <script>
        SELECT created_file.id, created_file.splitted_file_id, created_file.file, created_file.size, created_file.mime,
            created_file.encoding, created_file.status, created_file.width, created_file.height
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

    // Returns the program's executed_file_id: a locking read, so the graph can be locked before any plain read.
    @Select("SET STATEMENT innodb_lock_wait_timeout = 10 FOR SELECT executed_file_id FROM program WHERE id = #{id} FOR UPDATE")
    fun lockProgramRecording(id: Long): Long?

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

    // Single-program lock for manual label associations; the server's default lock wait, unlike the bulk locks.
    @Select("SELECT id FROM program WHERE id=#{id} FOR UPDATE")
    fun lockProgramRow(
        @Param("id") id: Long,
    ): Long?
}
