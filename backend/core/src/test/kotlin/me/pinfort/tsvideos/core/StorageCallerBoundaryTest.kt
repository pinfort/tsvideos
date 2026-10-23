package me.pinfort.tsvideos.core

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import me.pinfort.tsvideos.diagnostics.api.DiagnosticEnvironment
import java.io.File

/**
 * What remains of core's side of the storage boundary. The public signatures of the storage callers moved with their
 * owners: catalog's created-file operations to catalog's CatalogStorageCallerTest, and the verified upload to
 * processing's ProcessingStorageCallerTest (#165). Storage's own contracts are checked by storage's StorageContractTest
 * and its `checkStorageBoundaries` task.
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

        // The NAS implementation lives in the storage module; core keeps no NAS code at all.
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
    })
