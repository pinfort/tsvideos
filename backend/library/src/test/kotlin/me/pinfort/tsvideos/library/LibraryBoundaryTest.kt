package me.pinfort.tsvideos.library

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import java.io.File

/**
 * What the build's checkLibraryBoundaries (library/build.gradle.kts) cannot see by package names
 * (backend/docs/module-boundaries.md §5.2, §5.3): library SQL reads ledger tables only through its reference queries,
 * never writes or locks them, and reads no processing table. That persistence types stay out of the public operations
 * is enforced by the compiler, since the mappers and rows are internal.
 */
class LibraryBoundaryTest :
    ExpectSpec({
        // Run from library's project directory.
        val sources =
            File("src/main/kotlin/me/pinfort/tsvideos/library/persistence")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()

        expect("library SQL never writes or locks a ledger table") {
            sources.shouldNotBeEmpty()
            val ledger = "(?:program|executed_file|splitted_file|created_file|executed_file_tag|executed_file_check)\\b"
            val writes = Regex("""\b(?:INSERT\s+INTO|UPDATE|DELETE\s+FROM)\s+$ledger""", RegexOption.IGNORE_CASE)
            val locks = Regex("""\bFROM\s+$ledger[^"]*FOR\s+UPDATE""", RegexOption.IGNORE_CASE)
            sources
                .flatMap { file ->
                    val text = file.readText()
                    (writes.findAll(text) + locks.findAll(text)).map { "${file.name}: ${it.value}" }.toList()
                }.shouldBeEmpty()
        }

        expect("library SQL reads no processing table") {
            sources.shouldNotBeEmpty()
            val foreign = listOf("processing_", "upload_manifest", "recording_quality")
            sources
                .flatMap { file ->
                    val text = file.readText()
                    foreign.filter { it in text }.map { "${file.name}: $it" }
                }.shouldBeEmpty()
        }
    })
