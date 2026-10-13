package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.domain.LibraryKind
import me.pinfort.tsvideos.core.domain.LibraryLabel
import org.apache.ibatis.annotations.DeleteProvider
import org.apache.ibatis.annotations.InsertProvider
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.SelectProvider
import org.apache.ibatis.annotations.UpdateProvider

@Mapper
interface LibraryOrganizationMapper {
    @SelectProvider(type = LibraryOrganizationSql::class, method = "list")
    fun list(
        @Param("kind") kind: LibraryKind,
    ): List<LibraryLabel>

    @SelectProvider(type = LibraryOrganizationSql::class, method = "find")
    fun find(
        @Param("kind") kind: LibraryKind,
        @Param("id") id: Long,
    ): LibraryLabel?

    @SelectProvider(type = LibraryOrganizationSql::class, method = "lock")
    fun lock(
        @Param("kind") kind: LibraryKind,
        @Param("id") id: Long,
    ): Long?

    @Select("SELECT id FROM program WHERE id=#{id} FOR UPDATE")
    fun lockProgram(id: Long): Long?

    @Select("SELECT id FROM program WHERE id=#{id}")
    fun findProgram(id: Long): Long?

    @InsertProvider(type = LibraryOrganizationSql::class, method = "insert")
    @Options(useGeneratedKeys = true, keyProperty = "keyHolder.id")
    fun insert(
        @Param("kind") kind: LibraryKind,
        @Param("name") name: String,
        @Param("nameKey") nameKey: String,
        @Param("keyHolder") keyHolder: GeneratedKeyHolder,
    ): Int

    @InsertProvider(type = LibraryOrganizationSql::class, method = "reuse")
    @Options(useGeneratedKeys = true, keyProperty = "keyHolder.id")
    fun reuse(
        @Param("kind") kind: LibraryKind,
        @Param("name") name: String,
        @Param("nameKey") nameKey: String,
        @Param("keyHolder") keyHolder: GeneratedKeyHolder,
    ): Int

    @UpdateProvider(type = LibraryOrganizationSql::class, method = "rename")
    fun rename(
        @Param("kind") kind: LibraryKind,
        @Param("id") id: Long,
        @Param("name") name: String,
        @Param("nameKey") nameKey: String,
    ): Int

    @DeleteProvider(type = LibraryOrganizationSql::class, method = "delete")
    fun delete(
        @Param("kind") kind: LibraryKind,
        @Param("id") id: Long,
    ): Int

    @InsertProvider(type = LibraryOrganizationSql::class, method = "assign")
    fun assign(
        @Param("kind") kind: LibraryKind,
        @Param("id") id: Long,
        @Param("programId") programId: Long,
    ): Int

    @DeleteProvider(type = LibraryOrganizationSql::class, method = "unassign")
    fun unassign(
        @Param("kind") kind: LibraryKind,
        @Param("id") id: Long,
        @Param("programId") programId: Long,
    ): Int

    @SelectProvider(type = LibraryOrganizationSql::class, method = "forProgram")
    fun forProgram(
        @Param("kind") kind: LibraryKind,
        @Param("programId") programId: Long,
    ): List<LibraryLabel>
}
