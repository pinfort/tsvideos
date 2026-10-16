package me.pinfort.tsvideos.manager.api.response

import me.pinfort.tsvideos.core.domain.Program

data class SearchResponse(
    val programs: List<Program>,
    val total: Long,
    val limit: Int,
    val offset: Int,
)
