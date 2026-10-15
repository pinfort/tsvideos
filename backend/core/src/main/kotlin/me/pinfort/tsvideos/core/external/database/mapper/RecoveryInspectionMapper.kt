package me.pinfort.tsvideos.core.external.database.mapper

import me.pinfort.tsvideos.core.domain.ProcessingJob
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select

@Mapper
interface RecoveryInspectionMapper {
    @Select(
        "SELECT id,identity_key,input_path,input_size,modified_millis,state,executed_file_id,callback_claim,callback_manifest FROM processing_job WHERE input_path=#{path} ORDER BY id",
    )
    fun jobsByPath(path: String): List<ProcessingJob>

    @Select("SELECT id FROM program WHERE executed_file_id=#{id} ORDER BY id")
    fun programIdsByExecutedFileId(id: Long): List<Long>
}
