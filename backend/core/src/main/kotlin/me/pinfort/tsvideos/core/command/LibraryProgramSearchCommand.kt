package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.LibraryProgramFilter
import me.pinfort.tsvideos.core.domain.ProgramSearchPage
import me.pinfort.tsvideos.core.domain.ProgramSearchQuery
import me.pinfort.tsvideos.core.external.database.mapper.LibraryProgramSearchMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * The library's program search: catalog's ledger search, optionally narrowed to members of a manual collection
 * and/or tag. Without a library filter it is catalog's search itself; with one it runs library's reference query,
 * which applies the same ledger conditions, order and paging.
 */
@Component
class LibraryProgramSearchCommand(
    private val programs: ProgramCommand,
    private val mapper: LibraryProgramSearchMapper,
) {
    // One REPEATABLE_READ snapshot keeps total consistent with the page; catalog's search joins it.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun search(
        query: ProgramSearchQuery,
        filter: LibraryProgramFilter = LibraryProgramFilter(),
    ): ProgramSearchPage =
        if (filter.isEmpty) {
            programs.search(query)
        } else {
            ProgramSearchPage(mapper.search(query, filter).map { it.toDomain() }, mapper.count(query, filter), query.limit, query.offset)
        }
}
