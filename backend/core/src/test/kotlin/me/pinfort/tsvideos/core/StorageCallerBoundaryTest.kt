package me.pinfort.tsvideos.core

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.catalog.api.CreatedFileCommand
import me.pinfort.tsvideos.catalog.api.StreamableVideo
import me.pinfort.tsvideos.core.command.VerifiedUploadCommand
import me.pinfort.tsvideos.core.domain.UploadManifest
import me.pinfort.tsvideos.diagnostics.api.DiagnosticEnvironment
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility

/**
 * core's side of the storage boundary: that the public signatures of the callers do not expose implementation or HTTP
 * types. Storage's own contracts are checked by storage's StorageContractTest and its `checkStorageBoundaries` task; the
 * retry and cancellation classification of storage exceptions moved with ProcessingRetry to processing's
 * ProcessingStorageCallerTest (#164).
 */
class StorageCallerBoundaryTest :
    ExpectSpec({
        // core's application.yaml is what core's own tests (and any application without its own include list) load.
        context("configuration composition") {
            expect("core's configuration includes the storage profile and its packaged NAS settings") {
                val environment = DiagnosticEnvironment.load(emptyArray())
                environment.activeProfiles.toList() shouldContainAll listOf("core", "storage")
                environment.getProperty("samba.video-store-nas.url").shouldNotBeNull() shouldStartWith "smb:"
                environment.getProperty("samba.original-store-nas.url").shouldNotBeNull() shouldStartWith "smb:"
            }
        }

        context("public signatures of storage callers") {
            val callers: List<KClass<*>> =
                listOf(
                    CreatedFileCommand::class,
                    CreatedFileCatalog::class,
                    StreamableVideo::class,
                    VerifiedUploadCommand::class,
                    UploadManifest::class,
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

            // HTTP assembly (Resource, ResourceRegion) belongs to manager:api; catalog and its callers stay free of it.
            expect("catalog and CreatedFileCommand expose no Spring Resource or web type") {
                val leaks =
                    callers
                        .flatMap { signatures(it) }
                        .filter { "org.springframework.core.io." in it || "org.springframework.http." in it }
                leaks.shouldBeEmpty()
            }

            // The NAS implementation lives in the storage module; core reaches it only through storage's api package.
            expect("core sources neither import jcifs nor keep a NAS implementation package") {
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
                File(root, "me/pinfort/tsvideos/core/external/samba").exists() shouldBe false
                File(root, "me/pinfort/tsvideos/core/storage").exists() shouldBe false
            }
        }
    })
