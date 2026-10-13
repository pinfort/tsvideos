package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.InventoryExport
import me.pinfort.tsvideos.core.domain.InventoryProgram
import me.pinfort.tsvideos.core.domain.ProgramSearchQuery
import me.pinfort.tsvideos.core.external.database.dto.ProgramDto
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ExecutedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import me.pinfort.tsvideos.core.external.database.mapper.SplittedFileMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class InventoryExportCommand(
    private val programs: ProgramMapper,
    private val executed: ExecutedFileMapper,
    private val splits: SplittedFileMapper,
    private val created: CreatedFileMapper,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun export(
        ids: List<Long> = emptyList(),
        query: ProgramSearchQuery = ProgramSearchQuery(),
    ): InventoryExport {
        require(ids.all { it > 0 }) { "IDs must be positive" }
        require(ids.isEmpty() || query == ProgramSearchQuery()) { "Choose IDs or search filters" }
        require(query.offset == 0) { "Export does not accept a search offset" }
        val rows = mutableListOf<ProgramDto>()
        val missing = mutableListOf<Long>()
        if (ids.isNotEmpty()) {
            ids.distinct().sorted().forEach { id ->
                val row = programs.find(id)
                if (row == null) missing.add(id) else rows.add(row)
            }
        } else {
            var offset = 0
            while (true) {
                val page = programs.search(query.copy(limit = 500, offset = offset))
                rows.addAll(page)
                if (page.size < 500) break
                offset = Math.addExact(offset, 500)
            }
        }
        return InventoryExport(
            Instant.now(),
            rows.map { row ->
                InventoryProgram(
                    row.toDomain(),
                    executed.find(row.executedFileId)?.toDomain(),
                    splits.selectByExecutedFileId(row.executedFileId).sortedBy { it.id }.map { it.toDomain() },
                    created.selectByExecutedFileId(row.executedFileId).sortedBy { it.id }.map { it.toDomain() },
                )
            },
            missing,
        )
    }
}
