package me.pinfort.tsvideos.core.catalog

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.command.CreatedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileTagCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.domain.BulkSnapshot
import me.pinfort.tsvideos.core.domain.ProgramSearchQuery
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties

/**
 * The catalog boundary before catalog becomes its own module (#135, backend/docs/module-boundaries.md §5, §11.1):
 * only catalog code touches the ledger's mappers and DTOs, catalog's public operations expose no persistence type,
 * and catalog SQL and types know nothing of library or processing. Library-owned persistence (its own mappers and
 * reference queries) is not restricted here.
 */
class CatalogBoundaryTest :
    ExpectSpec({
        val core = "me/pinfort/tsvideos/core"

        // Ledger mappers and DTOs, by simple name: same-package code needs no import, so names are matched as words.
        val ledgerPersistence =
            listOf(
                "ProgramMapper",
                "ProgramSearchSql",
                "ExecutedFileMapper",
                "SplittedFileMapper",
                "CreatedFileMapper",
                "ExecutedFileTagMapper",
                "ExecutedFileCheckMapper",
                "BulkLibraryMapper",
                "StorageOverviewMapper",
                "LibraryStatsMapper",
                "ProgramDto",
                "ExecutedFileDto",
                "SplittedFileDto",
                "CreatedFileDto",
                "ExecutedCreatedFileDto",
                "StatsProgramDto",
                "StatsFileDto",
                "StorageReferenceDto",
            )

        val ledgerMapperFiles =
            listOf(
                "ProgramMapper",
                "ProgramSearchSql",
                "ExecutedFileMapper",
                "SplittedFileMapper",
                "CreatedFileMapper",
                "ExecutedFileTagMapper",
                "ExecutedFileCheckMapper",
                "BulkLibraryMapper",
                "StorageOverviewMapper",
                "LibraryStatsMapper",
            ).map { "$core/external/database/mapper/$it.kt" }

        // Paths relative to core's src/main/kotlin of everything catalog owns outside its own package.
        val catalogCommands =
            listOf("ProgramCommand", "CreatedFileCommand", "ExecutedFileCommand", "SplittedFileCommand", "ExecutedFileTagCommand")
        val catalogDtos =
            listOf(
                "ProgramDto",
                "ExecutedFileDto",
                "SplittedFileDto",
                "CreatedFileDto",
                "ExecutedCreatedFileDto",
                "StatsDto",
                "StorageReferenceDto",
            )
        val catalogFiles =
            catalogCommands.map { "$core/command/$it.kt" } + ledgerMapperFiles + catalogDtos.map { "$core/external/database/dto/$it.kt" }

        fun catalogOwned(path: String): Boolean = path.startsWith("$core/catalog/") || path in catalogFiles

        fun code(file: File): List<String> =
            file.readLines().filterNot { line ->
                line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            }

        // Run from core's project directory, like StorageCallerBoundaryTest.
        val roots =
            listOf(
                "src/main/kotlin",
                "../manager/api/src/main/kotlin",
                "../manager/console/src/main/kotlin",
                "../processor/infrastructure/src/main/kotlin",
                "../processor/console/src/main/kotlin",
            ).map(::File)

        context("source references") {
            expect("every scanned source root exists") {
                roots.filterNot { it.isDirectory }.map { it.path }.shouldBeEmpty()
            }

            expect("only catalog code references the ledger's mappers and DTOs") {
                val patterns = ledgerPersistence.associateWith { Regex("\\b$it\\b") }
                val offenders =
                    roots.flatMap { root ->
                        root
                            .walkTopDown()
                            .filter { it.isFile && it.extension == "kt" }
                            .mapNotNull { file ->
                                val path = file.relativeTo(root).invariantSeparatorsPath
                                if (root == roots.first() && catalogOwned(path)) return@mapNotNull null
                                val lines = code(file)
                                val used = patterns.filter { (_, pattern) -> lines.any { pattern.containsMatchIn(it) } }.keys
                                if (used.isEmpty()) null else "${root.path}/$path: $used"
                            }.toList()
                    }
                offenders.shouldBeEmpty()
            }

            expect("the scan covers the library and processing callers it guards") {
                val scanned =
                    roots
                        .first()
                        .walkTopDown()
                        .map { it.name }
                        .toSet()
                scanned shouldContainAll
                    listOf(
                        "BulkLibraryCommand.kt",
                        "CleanupPlanCommand.kt",
                        "DuplicateReviewCommand.kt",
                        "LibraryHealthCommand.kt",
                        "LibraryOrganizationCommand.kt",
                        "LibraryVerificationCommand.kt",
                        "VerifiedUploadCommand.kt",
                    )
            }

            // Library's own reference queries may join ledger tables (§5.2); the reverse direction is forbidden.
            expect("catalog SQL reads no library or processing table") {
                val foreign = listOf("library_", "health_verification", "processing_", "upload_manifest")
                val offenders =
                    ledgerMapperFiles.flatMap { path ->
                        val file = File("src/main/kotlin/$path")
                        file.isFile shouldBe true
                        val text = file.readText()
                        foreign.filter { it in text }.map { "$path: $it" }
                    }
                offenders.shouldBeEmpty()
            }

            expect("catalog code uses no library or processing type") {
                val foreign =
                    listOf(
                        "LibraryStatsQuery",
                        "LibraryProgramFilter",
                        "LibraryKind",
                        "LibraryLabel",
                        "HealthProgram",
                        "BulkSnapshot",
                        "BulkPlan",
                        "UploadManifest",
                        "ProcessingJob",
                    ).associateWith { Regex("\\b$it\\b") }
                val root = File("src/main/kotlin")
                val offenders =
                    root
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .filter { catalogOwned(it.relativeTo(root).invariantSeparatorsPath) }
                        .mapNotNull { file ->
                            val lines = code(file)
                            val used = foreign.filter { (_, pattern) -> lines.any { pattern.containsMatchIn(it) } }.keys
                            if (used.isEmpty()) null else "${file.relativeTo(root).invariantSeparatorsPath}: $used"
                        }.toList()
                offenders.shouldBeEmpty()
            }
        }

        context("public operations") {
            val operations: List<KClass<*>> =
                listOf(
                    CreatedFileCatalog::class,
                    LedgerLocks::class,
                    LedgerSnapshots::class,
                    ProgramInventory::class,
                    CatalogStatistics::class,
                    ProgramCommand::class,
                    CreatedFileCommand::class,
                    ExecutedFileCommand::class,
                    SplittedFileCommand::class,
                    ExecutedFileTagCommand::class,
                )
            val results: List<KClass<*>> =
                listOf(
                    ProgramSnapshot::class,
                    CreatedFileSnapshot::class,
                    ProgramSummary::class,
                    ProgramStatsRow::class,
                    FileStatsRow::class,
                    StorageReference::class,
                    StreamableVideo::class,
                    BulkSnapshot::class,
                )

            // Constructors are excluded: Spring injects the mappers there.
            fun memberSignatures(type: KClass<*>): List<String> =
                type.members
                    .filter { it.visibility == KVisibility.PUBLIC }
                    .flatMap { member ->
                        (member.parameters.map { it.type } + member.returnType).map { "${type.simpleName}.${member.name}: $it" }
                    }

            expect("no public member of a catalog operation or result exposes a mapper or DTO") {
                (operations + results)
                    .flatMap(::memberSignatures)
                    .filter { "me.pinfort.tsvideos.core.external.database." in it }
                    .shouldBeEmpty()
            }

            expect("the catalog search query has ledger conditions only") {
                val names = ProgramSearchQuery::class.memberProperties.map { it.name }
                names
                    .filter { it.contains("library", ignoreCase = true) || it.contains("collection", ignoreCase = true) }
                    .shouldBeEmpty()
            }
        }
    })
