package me.pinfort.tsvideos.core.domain

import java.time.Instant

data class InventoryExport(
    val generatedAt: Instant,
    val programs: List<InventoryProgram>,
    val missingIds: List<Long>,
)

data class InventoryProgram(
    val program: Program,
    val executedFile: ExecutedFile?,
    val splittedFiles: List<SplittedFile>,
    val createdFiles: List<CreatedFile>,
)
