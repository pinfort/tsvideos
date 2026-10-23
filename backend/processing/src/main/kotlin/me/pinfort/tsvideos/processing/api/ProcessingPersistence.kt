package me.pinfort.tsvideos.processing.api

/**
 * Where processing's MyBatis mappers live. Applications pass [MAPPER_PACKAGE] to `@MapperScan` so the mappers use the
 * application's single `SqlSessionFactory` and transaction manager; processing defines neither, and the package itself
 * stays internal (backend/docs/module-boundaries.md §7.1).
 */
object ProcessingPersistence {
    const val MAPPER_PACKAGE = "me.pinfort.tsvideos.processing.persistence"
}
