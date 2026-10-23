package me.pinfort.tsvideos.processing.persistence

import me.pinfort.tsvideos.processing.domain.ProcessingAttempt
import me.pinfort.tsvideos.processing.domain.ProcessingHistoryIdentity
import me.pinfort.tsvideos.processing.domain.ProcessingJob
import me.pinfort.tsvideos.processing.domain.ProcessingStageEvent
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

private const val ATTEMPT_COLUMNS =
    "id,job_id,attempt_number,kind,outcome," +
        "DATE_FORMAT(started_at,'%Y-%m-%dT%H:%i:%s.%fZ') started_at," +
        "DATE_FORMAT(ended_at,'%Y-%m-%dT%H:%i:%s.%fZ') ended_at," +
        "DATE_FORMAT(activity_at,'%Y-%m-%dT%H:%i:%s.%fZ') activity_at,error_code"
private const val STAGE_COLUMNS =
    "id,attempt_id,stage,outcome," +
        "DATE_FORMAT(started_at,'%Y-%m-%dT%H:%i:%s.%fZ') started_at," +
        "DATE_FORMAT(ended_at,'%Y-%m-%dT%H:%i:%s.%fZ') ended_at,error_code,artifact_type,artifact_id,artifact_path"

@Mapper
internal interface ProcessingHistoryMapper {
    @Insert(
        """
        INSERT INTO processing_history_identity(job_id,identity_key,input_path,input_size,modified_millis)
        VALUES(#{id},#{identityKey},#{inputPath},#{inputSize},#{modifiedMillis})
        ON DUPLICATE KEY UPDATE job_id=job_id
    """,
    )
    fun retain(job: ProcessingJob): Int

    @Select("SELECT * FROM processing_history_identity WHERE job_id=#{jobId} FOR UPDATE")
    fun lockIdentity(jobId: Long): ProcessingHistoryIdentity?

    @Update("UPDATE processing_history_identity SET last_attempt=last_attempt+1 WHERE job_id=#{jobId}")
    fun increment(jobId: Long): Int

    @Insert("INSERT INTO processing_attempt(job_id,attempt_number,kind,outcome) VALUES(#{jobId},#{number},#{kind},'ACTIVE')")
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    fun begin(
        jobId: Long,
        number: Int,
        kind: String,
        key: GeneratedKeyHolder,
    ): Int

    @Select("SELECT $ATTEMPT_COLUMNS FROM processing_attempt WHERE id=#{id} FOR UPDATE")
    fun lockAttempt(id: Long): ProcessingAttempt?

    @Select("SELECT $ATTEMPT_COLUMNS FROM processing_attempt WHERE id=#{id}")
    fun attempt(id: Long): ProcessingAttempt?

    @Update("UPDATE processing_attempt SET activity_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND outcome='ACTIVE'")
    fun heartbeat(id: Long): Int

    @Update(
        """
        UPDATE processing_attempt SET outcome=#{outcome},error_code=#{error},ended_at=UTC_TIMESTAMP(6),activity_at=UTC_TIMESTAMP(6)
        WHERE id=#{id} AND outcome='ACTIVE'
    """,
    )
    fun finish(
        id: Long,
        outcome: String,
        error: String?,
    ): Int

    @Insert("INSERT INTO processing_stage_event(attempt_id,stage,outcome) VALUES(#{attemptId},#{stage},'ACTIVE')")
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    fun startStage(
        attemptId: Long,
        stage: String,
        key: GeneratedKeyHolder,
    ): Int

    @Select("SELECT $STAGE_COLUMNS FROM processing_stage_event WHERE id=#{id}")
    fun stage(id: Long): ProcessingStageEvent?

    @Update(
        """
        UPDATE processing_stage_event SET outcome=#{outcome},error_code=#{error},ended_at=UTC_TIMESTAMP(6),
        artifact_type=#{artifactType},artifact_id=#{artifactId},artifact_path=#{artifactPath}
        WHERE id=#{id} AND outcome='ACTIVE'
    """,
    )
    fun finishStage(
        id: Long,
        outcome: String,
        error: String?,
        artifactType: String?,
        artifactId: Long?,
        artifactPath: String?,
    ): Int

    @Select("SELECT $STAGE_COLUMNS FROM processing_stage_event WHERE attempt_id=#{attemptId} ORDER BY id")
    fun stages(attemptId: Long): List<ProcessingStageEvent>

    @Select(
        """
        <script>
        SELECT * FROM processing_history_identity
        <if test="jobId != null">WHERE job_id=#{jobId}</if>
        ORDER BY job_id
        </script>
    """,
    )
    fun identities(jobId: Long?): List<ProcessingHistoryIdentity>

    @Select(
        """
        <script>
        SELECT $ATTEMPT_COLUMNS FROM processing_attempt
        <if test="jobId != null">WHERE job_id=#{jobId}</if>
        ORDER BY job_id,attempt_number
        </script>
    """,
    )
    fun attempts(jobId: Long?): List<ProcessingAttempt>

    @Select(
        """
        <script>
        SELECT $STAGE_COLUMNS FROM processing_stage_event WHERE attempt_id IN
        <foreach item="id" collection="attemptIds" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY attempt_id,id
        </script>
    """,
    )
    fun stagesOf(attemptIds: List<Long>): List<ProcessingStageEvent>

    @Select(
        """
        <script>
        SELECT id,identity_key,input_path,input_size,modified_millis,state,executed_file_id,callback_claim,callback_manifest,updated_at
        FROM processing_job
        <if test="jobId != null">WHERE id=#{jobId}</if>
        ORDER BY id
        </script>
    """,
    )
    fun liveJobs(jobId: Long?): List<ProcessingJob>
}
