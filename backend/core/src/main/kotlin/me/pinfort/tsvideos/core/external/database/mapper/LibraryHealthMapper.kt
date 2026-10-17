package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.domain.HealthProgram
import me.pinfort.tsvideos.core.domain.HealthRun
import me.pinfort.tsvideos.core.external.database.dto.HealthFindingDto
import me.pinfort.tsvideos.core.external.database.dto.HealthRunDto
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import java.time.LocalDateTime

@Mapper
interface LibraryHealthMapper {
    @Select(
        "SELECT p.id, p.name, p.status, e.drops FROM program p LEFT JOIN executed_file e ON e.id=p.executed_file_id WHERE p.status='ERROR' ORDER BY p.id",
    )
    fun failedPrograms(): List<HealthProgram>

    @Select(
        "SELECT p.id, p.name, p.status, e.drops FROM program p JOIN executed_file e ON e.id=p.executed_file_id WHERE e.drops>0 ORDER BY p.id",
    )
    fun droppedPrograms(): List<HealthProgram>

    /** Programs still present among one run's findings; a finding whose program is absent refers to a deleted program. */
    @Select(
        """
        SELECT p.id, p.name, p.status, e.drops FROM program p LEFT JOIN executed_file e ON e.id=p.executed_file_id
        WHERE p.id IN (SELECT program_id FROM health_verification_finding WHERE run_id=#{runId}) ORDER BY p.id
    """,
    )
    fun findingPrograms(runId: Long): List<HealthProgram>

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
}
