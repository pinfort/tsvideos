package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.domain.ProcessingCheckpoint
import me.pinfort.tsvideos.core.domain.ProcessingJob
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface ProcessingRecoveryMapper {
    @Select(
        "SELECT id,identity_key,input_path,input_size,modified_millis,state,executed_file_id,callback_claim,callback_manifest FROM processing_job WHERE id=#{id}",
    )
    fun job(id: Long): ProcessingJob?

    @Select(
        "SELECT id,identity_key,input_path,input_size,modified_millis,state,executed_file_id,callback_claim,callback_manifest FROM processing_job WHERE id=#{id} FOR UPDATE",
    )
    fun lockJob(id: Long): ProcessingJob?

    @Select("SELECT processing_claim FROM processing_job WHERE id=#{id}")
    fun owner(id: Long): String?

    @Select("SELECT COUNT(*) FROM processing_attempt WHERE job_id=#{id} AND outcome='ACTIVE'")
    fun active(id: Long): Int

    @Select("SELECT COUNT(*) FROM processing_attempt WHERE job_id=#{id} AND kind='CALLBACK' AND outcome='ACTIVE'")
    fun activeCallback(id: Long): Int

    @Select("SELECT COUNT(*) FROM processing_attempt WHERE job_id=#{id}")
    fun attempts(id: Long): Int

    @Select("SELECT COUNT(*) FROM processing_attempt WHERE id=#{attemptId} AND job_id=#{jobId} AND outcome='ACTIVE'")
    fun activeAttempt(
        jobId: Long,
        attemptId: Long,
    ): Int

    @Update(
        "UPDATE processing_job SET processing_claim=#{token},executed_file_id=CASE WHEN state='RETRYABLE' THEN NULL ELSE executed_file_id END,state='CLAIMED' WHERE id=#{id} AND processing_claim IS NULL AND callback_claim IS NULL AND state IN ('CLAIMED','RETRYABLE')",
    )
    fun claim(
        id: Long,
        token: String,
    ): Int

    @Update("UPDATE processing_job SET processing_claim=NULL WHERE id=#{id} AND processing_claim=#{token}")
    fun release(
        id: Long,
        token: String,
    ): Int

    @Insert("INSERT INTO processing_checkpoint(job_id,attempt_id,stage,evidence) VALUES(#{jobId},#{attemptId},#{stage},#{evidence})")
    fun checkpoint(
        jobId: Long,
        attemptId: Long,
        stage: String,
        evidence: String,
    ): Int

    @Select(
        "SELECT c.* FROM processing_checkpoint c LEFT JOIN processing_checkpoint_invalidation i ON i.checkpoint_id=c.id WHERE c.job_id=#{jobId} AND i.checkpoint_id IS NULL ORDER BY c.id",
    )
    fun checkpoints(jobId: Long): List<ProcessingCheckpoint>

    @Insert(
        "INSERT INTO processing_checkpoint_invalidation(checkpoint_id,reason) SELECT c.id,'ROLLED_BACK' FROM processing_checkpoint c LEFT JOIN processing_checkpoint_invalidation i ON i.checkpoint_id=c.id WHERE c.job_id=#{jobId} AND i.checkpoint_id IS NULL AND c.stage IN ('REGISTER','SPLIT','COMPRESS','UPLOAD_ORIGINAL')",
    )
    fun invalidate(jobId: Long): Int

    @Select("SELECT manifest FROM processing_callback_input WHERE job_id=#{jobId}")
    fun callback(jobId: Long): String?

    @Insert("INSERT INTO processing_callback_input(job_id,manifest) VALUES(#{jobId},#{manifest})")
    fun callbackInput(
        jobId: Long,
        manifest: String,
    ): Int
}
