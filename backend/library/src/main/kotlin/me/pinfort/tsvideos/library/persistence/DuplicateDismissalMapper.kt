package me.pinfort.tsvideos.library.persistence

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import java.time.LocalDateTime

/**
 * Library-owned dismissals of reviewed duplicate groups (#194). A group is keyed by `reason:sorted,program,ids`, the
 * same text the generated `group_key` hashes; this mapper reads no ledger table.
 */
@Mapper
internal interface DuplicateDismissalMapper {
    @Select("SELECT CONCAT(reason,':',program_ids) FROM library_duplicate_dismissal")
    fun keys(): List<String>

    // Hiding an already hidden group keeps the first dismissal time.
    @Insert(
        """
        INSERT INTO library_duplicate_dismissal(reason,program_ids,dismissed_at) VALUES(#{reason},#{programIds},#{dismissedAt})
        ON DUPLICATE KEY UPDATE id=id
    """,
    )
    fun insert(
        reason: String,
        programIds: String,
        dismissedAt: LocalDateTime,
    ): Int

    @Delete("DELETE FROM library_duplicate_dismissal WHERE group_key=UNHEX(SHA2(CONCAT(#{reason},':',#{programIds}),256))")
    fun delete(
        reason: String,
        programIds: String,
    ): Int
}
