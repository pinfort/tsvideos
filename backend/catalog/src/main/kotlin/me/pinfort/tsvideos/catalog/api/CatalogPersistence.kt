package me.pinfort.tsvideos.catalog.api

/**
 * Where catalog's MyBatis mappers live. Applications pass [MAPPER_PACKAGE] to `@MapperScan` so the mappers use the
 * application's single `SqlSessionFactory` and transaction manager; catalog defines neither, and the package itself
 * stays internal (backend/docs/module-boundaries.md §7.1).
 */
object CatalogPersistence {
    const val MAPPER_PACKAGE = "me.pinfort.tsvideos.catalog.persistence"
}
