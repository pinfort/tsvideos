package me.pinfort.tsvideos.catalog

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import me.pinfort.tsvideos.catalog.domain.ProgramSearchQuery
import java.io.File
import kotlin.reflect.full.memberProperties

/**
 * What the build's checkCatalogBoundaries (catalog/build.gradle.kts) cannot see by package names
 * (backend/docs/module-boundaries.md §5.2, §11.7): catalog SQL reads no library or processing table, and the catalog
 * search has ledger conditions only. That persistence types stay out of the public operations is enforced by the
 * compiler, since the mappers and DTOs are internal.
 */
class CatalogBoundaryTest :
    ExpectSpec({
        // Run from catalog's project directory.
        val persistence = File("src/main/kotlin/me/pinfort/tsvideos/catalog/persistence")

        expect("catalog SQL reads no library or processing table") {
            val sources = persistence.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            sources.shouldNotBeEmpty()
            val foreign = listOf("library_", "health_verification", "processing_", "upload_manifest")
            sources
                .flatMap { file ->
                    val text = file.readText()
                    foreign.filter { it in text }.map { "${file.name}: $it" }
                }.shouldBeEmpty()
        }

        expect("the catalog search query has ledger conditions only") {
            ProgramSearchQuery::class
                .memberProperties
                .map { it.name }
                .filter { it.contains("library", ignoreCase = true) || it.contains("collection", ignoreCase = true) }
                .shouldBeEmpty()
        }
    })
