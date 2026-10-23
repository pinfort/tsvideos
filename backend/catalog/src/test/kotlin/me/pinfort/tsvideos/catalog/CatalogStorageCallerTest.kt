package me.pinfort.tsvideos.catalog

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.catalog.api.CreatedFileCommand
import me.pinfort.tsvideos.catalog.api.StreamableVideo
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility

/**
 * catalog's side of the storage boundary (moved from core's StorageCallerBoundaryTest in #165): the public signatures
 * of catalog's storage callers do not expose implementation or HTTP types. Storage's own contracts are checked by
 * storage's StorageContractTest and its `checkStorageBoundaries` task; processing's callers by processing's
 * ProcessingStorageCallerTest.
 */
class CatalogStorageCallerTest :
    ExpectSpec({
        context("public signatures of storage callers") {
            val callers: List<KClass<*>> =
                listOf(
                    CreatedFileCommand::class,
                    CreatedFileCatalog::class,
                    StreamableVideo::class,
                )

            fun signatures(type: KClass<*>): List<String> {
                val members = type.members.filter { it.visibility == KVisibility.PUBLIC }
                val constructors = type.constructors.filter { it.visibility == KVisibility.PUBLIC }
                val memberSignatures =
                    members.flatMap { member ->
                        (member.parameters.map { it.type } + member.returnType).map { "${type.simpleName}.${member.name}: $it" }
                    }
                val constructorSignatures =
                    constructors.flatMap { constructor ->
                        constructor.parameters.map { "${type.simpleName}(): ${it.type}" }
                    }
                return memberSignatures + constructorSignatures
            }

            expect("no public member or constructor mentions a jcifs type") {
                val leaks = callers.flatMap { signatures(it) }.filter { "jcifs." in it }
                leaks.shouldBeEmpty()
            }

            // HTTP assembly (Resource, ResourceRegion) belongs to manager:api; catalog stays free of it.
            expect("catalog's created-file operations expose no Spring Resource or web type") {
                val leaks =
                    callers
                        .flatMap { signatures(it) }
                        .filter { "org.springframework.core.io." in it || "org.springframework.http." in it }
                leaks.shouldBeEmpty()
            }

            // The NAS implementation lives in the storage module; catalog reaches it only through storage's api package.
            expect("catalog sources do not import jcifs") {
                val root = File("src/main/kotlin")
                root.isDirectory shouldBe true
                val offenders =
                    root
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .filter { file -> file.readLines().any { it.startsWith("import jcifs.") } }
                        .map { it.relativeTo(root).invariantSeparatorsPath }
                        .toList()
                offenders.shouldBeEmpty()
            }
        }
    })
