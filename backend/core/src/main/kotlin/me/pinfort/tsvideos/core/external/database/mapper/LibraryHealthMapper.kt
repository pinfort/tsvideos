package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.domain.HealthProgram
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
    @Select("SELECT p.id, p.name, p.status, e.drops FROM program p LEFT JOIN executed_file e ON e.id=p.executed_file_id ORDER BY p.id")
    fun programs(): List<HealthProgram>

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
        INSERT INTO health_verification_finding(run_id,program_id,file_id,file,status,checked_at,expected_bytes,actual_bytes,reason)
        VALUES(#{runId},#{finding.programId},#{finding.fileId},#{finding.file},#{finding.status},#{finding.checkedAt},#{finding.expectedBytes},#{finding.actualBytes},#{finding.reason})
    """,
    )
    fun insertFinding(
        runId: Long,
        finding: HealthFindingDto,
    ): Int

    @Update(
        """
        UPDATE health_verification_run SET state=#{state},completed_at=#{completedAt},failure_code=#{failureCode},active_marker=NULL
        WHERE id=#{runId} AND state='RUNNING'
    """,
    )
    fun finish(
        runId: Long,
        state: String,
        completedAt: LocalDateTime,
        failureCode: String?,
    ): Int
}
