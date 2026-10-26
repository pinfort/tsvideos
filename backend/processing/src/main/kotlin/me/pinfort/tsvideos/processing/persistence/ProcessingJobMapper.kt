package me.pinfort.tsvideos.processing.persistence

import me.pinfort.tsvideos.processing.domain.ProcessingJob
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

private const val IN_STATES = "<foreach item='state' collection='states' open='(' separator=',' close=')'>#{state}</foreach>"

@Mapper
internal interface ProcessingJobMapper {
    @Insert(
        "INSERT INTO processing_job(identity_key,input_path,input_size,modified_millis,state) VALUES(#{key},#{path},#{size},#{modified},#{state})",
    )
    fun claim(
        key: String,
        path: String,
        size: Long,
        modified: Long,
        state: ProcessingJob.State,
    ): Int

    @Select(
        "SELECT id,identity_key,input_path,input_size,modified_millis,state,executed_file_id,callback_claim,callback_manifest,updated_at FROM processing_job WHERE identity_key=#{key}",
    )
    fun find(key: String): ProcessingJob?

    @Select(
        "SELECT id,identity_key,input_path,input_size,modified_millis,state,executed_file_id,callback_claim,callback_manifest,updated_at FROM processing_job WHERE executed_file_id=#{id} ORDER BY id DESC LIMIT 1",
    )
    fun findByExecutedFile(id: Long): ProcessingJob?

    @Update(
        "UPDATE processing_job SET executed_file_id=#{executedId}, updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND state=#{state} AND executed_file_id IS NULL",
    )
    fun link(
        id: Long,
        executedId: Long,
        state: ProcessingJob.State,
    ): Int

    @Update("UPDATE processing_job SET state=#{next}, updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND state=#{expected}")
    fun transition(
        id: Long,
        expected: ProcessingJob.State,
        next: ProcessingJob.State,
    ): Int

    @Update(
        "<script>UPDATE processing_job SET callback_claim=#{token}, updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND callback_claim IS NULL AND state IN $IN_STATES</script>",
    )
    fun claimCallback(
        id: Long,
        token: String,
        states: Set<ProcessingJob.State>,
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
        "<script>UPDATE processing_job SET state=#{next}, updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND state=#{expected} AND callback_claim IS NULL AND state IN $IN_STATES</script>",
    )
    fun reconcile(
        id: Long,
        expected: ProcessingJob.State,
        next: ProcessingJob.State,
        states: Set<ProcessingJob.State>,
    ): Int

    @Delete(
        "<script>DELETE FROM processing_job WHERE executed_file_id=#{id} AND callback_claim IS NULL AND state IN $IN_STATES</script>",
    )
    fun removeReset(
        id: Long,
        states: Set<ProcessingJob.State>,
    ): Int

    @Update(
        "UPDATE processing_job SET state=#{next},executed_file_id=NULL,callback_manifest=NULL, updated_at=UTC_TIMESTAMP(6) WHERE identity_key=#{key} AND state=#{expected} AND callback_claim IS NULL",
    )
    fun retry(
        key: String,
        expected: ProcessingJob.State,
        next: ProcessingJob.State,
    ): Int

    @Update(
        "<script>UPDATE processing_job SET state=#{next}, updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND callback_claim IS NULL AND state IN $IN_STATES</script>",
    )
    fun prepareReset(
        id: Long,
        next: ProcessingJob.State,
        states: Set<ProcessingJob.State>,
    ): Int

    @Select(
        "SELECT id,identity_key,input_path,input_size,modified_millis,state,executed_file_id,callback_claim,callback_manifest,updated_at FROM processing_job WHERE input_path=#{path} ORDER BY id DESC LIMIT 1",
    )
    fun findByPath(path: String): ProcessingJob?
}
