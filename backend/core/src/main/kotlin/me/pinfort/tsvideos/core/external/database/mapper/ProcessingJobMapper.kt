package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.domain.ProcessingJob
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface ProcessingJobMapper {
    @Insert(
        "INSERT INTO processing_job(identity_key,input_path,input_size,modified_millis,state) VALUES(#{key},#{path},#{size},#{modified},'CLAIMED')",
    )
    fun claim(
        key: String,
        path: String,
        size: Long,
        modified: Long,
    ): Int

    @Select(
        "SELECT id,identity_key,input_path,input_size,modified_millis,state,executed_file_id,callback_claim,callback_manifest FROM processing_job WHERE identity_key=#{key}",
    )
    fun find(key: String): ProcessingJob?

    @Select(
        "SELECT id,identity_key,input_path,input_size,modified_millis,state,executed_file_id,callback_claim,callback_manifest FROM processing_job WHERE executed_file_id=#{id} ORDER BY id DESC LIMIT 1",
    )
    fun findByExecutedFile(id: Long): ProcessingJob?

    @Update(
        "UPDATE processing_job SET executed_file_id=#{executedId}, updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND state='CLAIMED' AND executed_file_id IS NULL",
    )
    fun link(
        id: Long,
        executedId: Long,
    ): Int

    @Update("UPDATE processing_job SET state=#{next}, updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND state=#{expected}")
    fun transition(
        id: Long,
        expected: String,
        next: String,
    ): Int

    @Update(
        "UPDATE processing_job SET callback_claim=#{token}, updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND callback_claim IS NULL AND state IN ('SUBMITTED','SUBMISSION_UNKNOWN','COMPLETED')",
    )
    fun claimCallback(
        id: Long,
        token: String,
    ): Int

    @Update("UPDATE processing_job SET callback_claim=NULL, updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND callback_claim=#{token}")
    fun releaseCallback(
        id: Long,
        token: String,
    ): Int

    @Update(
        "UPDATE processing_job SET callback_manifest=#{manifest}, updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND callback_claim=#{token} AND (callback_manifest IS NULL OR callback_manifest=#{manifest})",
    )
    fun manifest(
        id: Long,
        token: String,
        manifest: String,
    ): Int

    @Update(
        "UPDATE processing_job SET state=#{next}, updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND state=#{expected} AND callback_claim IS NULL AND state IN ('SUBMITTED','SUBMISSION_UNKNOWN','COMPLETED')",
    )
    fun reconcile(
        id: Long,
        expected: String,
        next: String,
    ): Int

    @org.apache.ibatis.annotations.Delete(
        "DELETE FROM processing_job WHERE executed_file_id=#{id} AND callback_claim IS NULL AND state IN ('REJECTED','COMPLETED','RETRYABLE','RESETTING')",
    )
    fun removeReset(id: Long): Int

    @Update(
        "UPDATE processing_job SET state='CLAIMED',executed_file_id=NULL, updated_at=UTC_TIMESTAMP(6) WHERE identity_key=#{key} AND state='RETRYABLE' AND callback_claim IS NULL",
    )
    fun retry(key: String): Int

    @Update(
        "UPDATE processing_job SET state='RESETTING', updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND callback_claim IS NULL AND state IN ('COMPLETED','REJECTED','RETRYABLE','RESETTING')",
    )
    fun prepareReset(id: Long): Int

    @Select(
        "SELECT id,identity_key,input_path,input_size,modified_millis,state,executed_file_id,callback_claim,callback_manifest FROM processing_job WHERE input_path=#{path} ORDER BY id DESC LIMIT 1",
    )
    fun findByPath(path: String): ProcessingJob?
}
