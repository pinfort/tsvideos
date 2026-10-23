package me.pinfort.tsvideos.processing.persistence

import me.pinfort.tsvideos.processing.domain.UploadManifest
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
internal interface UploadManifestMapper {
    @Insert(
        """
        INSERT INTO upload_manifest(job_id,artifact_key,destination_key,nas_type,final_path,temporary_path,
        source_path,source_size,source_modified,source_sha256,checksum_required,state)
        VALUES(#{jobId},#{artifactKey},#{destinationKey},#{nasType},#{finalPath},#{temporaryPath},
        #{sourcePath},#{sourceSize},#{sourceModified},#{sourceSha256},#{checksumRequired},'NEW')
    """,
    )
    fun insert(manifest: UploadManifest): Int

    @Select("SELECT * FROM upload_manifest WHERE job_id=#{jobId} AND artifact_key=#{artifactKey}")
    fun find(
        jobId: Long,
        artifactKey: String,
    ): UploadManifest?

    @Select("SELECT * FROM upload_manifest WHERE destination_key=#{destinationKey}")
    fun findByDestination(destinationKey: String): UploadManifest?

    @Select("SELECT * FROM upload_manifest WHERE id=#{id}")
    fun get(id: Long): UploadManifest?

    @Select("SELECT * FROM upload_manifest WHERE job_id=#{jobId} ORDER BY id")
    fun forJob(jobId: Long): List<UploadManifest>

    @Update("UPDATE upload_manifest SET claim_token=#{token},updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND claim_token IS NULL")
    fun claim(
        id: Long,
        token: String,
    ): Int

    @Update("UPDATE upload_manifest SET claim_token=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND claim_token=#{token}")
    fun release(
        id: Long,
        token: String,
    ): Int

    @Update(
        """
        UPDATE upload_manifest SET state=#{next},updated_at=UTC_TIMESTAMP(6)
        WHERE id=#{id} AND state=#{expected} AND claim_token=#{token}
    """,
    )
    fun transition(
        id: Long,
        token: String,
        expected: UploadManifest.State,
        next: UploadManifest.State,
    ): Int

    @Update(
        """
        UPDATE upload_manifest SET state='REGISTERED',created_file_id=#{createdFileId},updated_at=UTC_TIMESTAMP(6)
        WHERE id=#{id} AND state='FINALIZED' AND claim_token=#{token}
    """,
    )
    fun register(
        id: Long,
        token: String,
        createdFileId: Long,
    ): Int

    @Delete("DELETE FROM upload_manifest WHERE id=#{id} AND claim_token=#{token}")
    fun remove(
        id: Long,
        token: String,
    ): Int
}
