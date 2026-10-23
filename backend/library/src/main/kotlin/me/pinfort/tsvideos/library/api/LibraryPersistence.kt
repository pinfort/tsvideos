package me.pinfort.tsvideos.library.api

/**
 * Where library's MyBatis mappers live. Applications pass [MAPPER_PACKAGE] to `@MapperScan` so the mappers use the
 * application's single `SqlSessionFactory` and transaction manager; library defines neither, and the package itself
 * stays internal (backend/docs/module-boundaries.md §7.1).
 */
object LibraryPersistence {
    const val MAPPER_PACKAGE = "me.pinfort.tsvideos.library.persistence"
}
