package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.domain.RecordingQualityReport
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface RecordingQualityMapper {
    @Select(
        "SELECT id,identity_key,input_path,input_size,modified_millis,state,executed_file_id,callback_claim,callback_manifest FROM processing_job WHERE id=#{id} FOR UPDATE",
    )
    fun lockJob(id: Long): ProcessingJob?

    @Select("SELECT COUNT(*) FROM processing_attempt WHERE id=#{attemptId} AND job_id=#{jobId} AND outcome='ACTIVE'")
    fun ownedAttempt(
        attemptId: Long,
        jobId: Long,
    ): Int

    @Select("SELECT COUNT(*) FROM processing_attempt WHERE job_id=#{jobId} AND outcome='ACTIVE'")
    fun activeAttempts(jobId: Long): Int

    @Update(
        "UPDATE processing_job SET state=#{next},updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND state=#{expected} AND executed_file_id IS NULL AND callback_claim IS NULL",
    )
    fun transition(
        id: Long,
        expected: String,
        next: String,
    ): Int

    @Insert(
        """
        INSERT INTO processing_quality_report(attempt_id,job_id,action,source_path,identity_key,findings_json)
        VALUES(#{attemptId},#{jobId},#{action},#{sourcePath},#{identityKey},#{findingsJson})
    """,
    )
    fun insert(report: RecordingQualityReport): Int

    @Select(
        """
        SELECT attempt_id,job_id,DATE_FORMAT(checked_at,'%Y-%m-%dT%H:%i:%s.%fZ') checked_at,
        action,source_path,identity_key,findings_json FROM processing_quality_report
        WHERE job_id=#{jobId} ORDER BY attempt_id
    """,
    )
    fun forJob(jobId: Long): List<RecordingQualityReport>
}
