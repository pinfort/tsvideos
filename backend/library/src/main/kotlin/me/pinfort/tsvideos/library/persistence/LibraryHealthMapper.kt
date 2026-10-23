package me.pinfort.tsvideos.library.persistence

import me.pinfort.tsvideos.library.domain.HealthRun
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import java.time.LocalDateTime

/**
 * Library-owned health runs and findings. Program names and states come from catalog's ProgramInventory, not from
 * SQL here: this mapper reads no ledger table.
 */
@Mapper
internal interface LibraryHealthMapper {
    @Insert("INSERT INTO health_verification_run(state,started_at,active_marker) VALUES('RUNNING',#{startedAt},1)")
    @Options(useGeneratedKeys = true, keyProperty = "keyHolder.id")
    fun start(
        startedAt: LocalDateTime,
        keyHolder: GeneratedKeyHolder,
    ): Int

    @Select("SELECT id,state,started_at,completed_at,failure_code FROM health_verification_run ORDER BY id DESC LIMIT 1")
    fun latest(): HealthRunDto?

    @Select(
        "SELECT id,state,started_at,completed_at,failure_code FROM health_verification_run WHERE state='COMPLETED' ORDER BY completed_at DESC,id DESC LIMIT 1",
    )
    fun completed(): HealthRunDto?

    @Select(
        "SELECT program_id,file_id,file,status,checked_at,expected_bytes,actual_bytes,reason FROM health_verification_finding WHERE run_id=#{runId} ORDER BY id",
    )
    fun findings(runId: Long): List<HealthFindingDto>

    @Insert(
        """
        <script>
        INSERT INTO health_verification_finding(run_id,program_id,file_id,file,status,checked_at,expected_bytes,actual_bytes,reason)
        VALUES
        <foreach item="f" collection="findings" separator=",">
            (#{runId},#{f.programId},#{f.fileId},#{f.file},#{f.status},#{f.checkedAt},#{f.expectedBytes},#{f.actualBytes},#{f.reason})
        </foreach>
        </script>
    """,
    )
    fun insertFindings(
        runId: Long,
        findings: List<HealthFindingDto>,
    ): Int

    @Update(
        """
        UPDATE health_verification_run SET state=#{state},completed_at=#{completedAt},failure_code=#{failureCode},active_marker=NULL
        WHERE id=#{runId} AND state='RUNNING'
    """,
    )
    fun finish(
        runId: Long,
        state: HealthRun.State,
        completedAt: LocalDateTime,
        failureCode: String?,
    ): Int

    @Update(
        """
        UPDATE health_verification_run SET state='FAILED',completed_at=#{completedAt},failure_code=#{failureCode},active_marker=NULL
        WHERE state='RUNNING'
    """,
    )
    fun failAllRunning(
        completedAt: LocalDateTime,
        failureCode: String,
    ): Int
}
