package me.pinfort.tsvideos.core.storage

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import me.pinfort.tsvideos.core.command.CreatedFileCommand
import me.pinfort.tsvideos.core.command.VerifiedUploadCommand
import me.pinfort.tsvideos.core.component.NasDestinationResolver
import me.pinfort.tsvideos.core.component.NasDiagnosticProbe
import me.pinfort.tsvideos.core.component.ProcessingRetry
import me.pinfort.tsvideos.core.component.TransientProcessingFailure
import me.pinfort.tsvideos.core.domain.UploadManifest
import me.pinfort.tsvideos.core.external.samba.DiagnosticNasClient
import me.pinfort.tsvideos.core.external.samba.NasComponent
import me.pinfort.tsvideos.core.external.samba.NasFileVerifier
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.core.external.samba.SmbFileResource
import me.pinfort.tsvideos.core.external.samba.VerifiedUploadNas
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility

class NasStorageBoundaryTest :
    ExpectSpec({
        context("NasStore compatibility") {
            expect("keeps the persisted constant names and order of the former SambaClient.NasType") {
                NasStore.entries.map { it.name } shouldContainExactly listOf("VIDEO_STORE_NAS", "ORIGINAL_STORE_NAS")
                NasStore.valueOf("VIDEO_STORE_NAS") shouldBe NasStore.VIDEO_STORE_NAS
                NasStore.valueOf("ORIGINAL_STORE_NAS") shouldBe NasStore.ORIGINAL_STORE_NAS
            }
        }

        context("storage exceptions") {
            expect("both kinds are storage exceptions (IOException) carrying the implementation cause") {
                val cause = IOException("implementation")
                listOf<NasStorageException>(
                    NasAccessException("NAS read failed", cause),
                    NasAuthenticationException("NAS read failed: credentials rejected", cause),
                ).forEach {
                    it.shouldBeInstanceOf<IOException>()
                    it.cause shouldBe cause
                }
            }

            expect("an authentication failure stays non-retryable even under a network timeout") {
                TransientProcessingFailure.reason(NasAuthenticationException("NAS upload failed", SocketTimeoutException())) shouldBe null
                TransientProcessingFailure.reason(IOException("wrapped", NasAuthenticationException("denied"))) shouldBe null
            }

            expect("an access failure caused by a network timeout stays retryable") {
                TransientProcessingFailure.reason(NasAccessException("NAS upload failed", SocketTimeoutException())) shouldBe
                    "NETWORK_UNAVAILABLE"
            }
        }

        context("public NAS contracts") {
            // Public (not internal/private) signatures of storage operations and their callers must not expose jcifs.
            val contracts: List<KClass<*>> =
                listOf(
                    NasStore::class,
                    NasStorageException::class,
                    NasAccessException::class,
                    NasAuthenticationException::class,
                    SambaClient::class,
                    NasComponent::class,
                    VerifiedUploadNas::class,
                    NasFileVerifier::class,
                    NasFileVerifier.Session::class,
                    SmbFileResource::class,
                    DiagnosticNasClient::class,
                    DiagnosticNasClient.Connection::class,
                    DiagnosticNasClient.Directory::class,
                    DiagnosticNasClient.FileHandle::class,
                    NasDiagnosticProbe::class,
                    NasDestinationResolver::class,
                    CreatedFileCommand::class,
                    VerifiedUploadCommand::class,
                    UploadManifest::class,
                    ProcessingRetry::class,
                    TransientProcessingFailure::class,
                )

            expect("no public member or constructor mentions a jcifs type") {
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

                val leaks = contracts.flatMap { signatures(it) }.filter { "jcifs." in it }
                leaks.shouldBeEmpty()
            }

            // The storage package holds only implementation-independent declarations; jcifs stays in external/samba.
            expect("jcifs is imported only by the Samba implementation") {
                val root = File("src/main/kotlin")
                root.isDirectory shouldBe true
                val allowed = listOf("me/pinfort/tsvideos/core/external/samba/")
                val offenders =
                    root
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .filter { file -> file.readLines().any { it.startsWith("import jcifs.") } }
                        .map { it.relativeTo(root).invariantSeparatorsPath }
                        .filterNot { path -> allowed.any { path.startsWith(it) } }
                        .toList()
                offenders.shouldBeEmpty()
            }
        }
    })
