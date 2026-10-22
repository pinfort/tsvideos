package me.pinfort.tsvideos.manager.api.response

import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.ProgramDetail

data class ProgramDetailResponse(
    val program: ProgramDetail,
    val videoFiles: List<CreatedFile>,
)
