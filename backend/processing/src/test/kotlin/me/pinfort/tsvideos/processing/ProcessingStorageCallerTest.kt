package me.pinfort.tsvideos.processing

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.processing.api.NasDestinationResolver
import me.pinfort.tsvideos.processing.api.ProcessingCancellation
import me.pinfort.tsvideos.processing.api.ProcessingRetry
import me.pinfort.tsvideos.processing.api.TransientProcessingFailure
import me.pinfort.tsvideos.storage.api.NasAccessException
import me.pinfort.tsvideos.storage.api.NasAuthenticationException
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility

/**
 * processing's side of the storage boundary (moved from core's StorageCallerBoundaryTest in #164): how processing
 * classifies storage exceptions, and that the public signatures of its storage callers do not expose implementation or
 * HTTP types. Storage's own contracts are checked by storage's StorageContractTest and its `checkStorageBoundaries` task.
 */
class ProcessingStorageCallerTest :
    ExpectSpec({
        context("retry and cancellation classification of storage exceptions") {
            expect("an authentication failure stays non-retryable even under a network timeout") {
                TransientProcessingFailure.reason(NasAuthenticationException("NAS upload failed", SocketTimeoutException())) shouldBe null
                TransientProcessingFailure.reason(IOException("wrapped", NasAuthenticationException("denied"))) shouldBe null
            }

            // Storage keeps the implementation exception (and its own causes) as the cause of the translated one.
            expect("an access failure caused by a network timeout stays retryable") {
                TransientProcessingFailure.reason(NasAccessException("NAS upload failed", SocketTimeoutException())) shouldBe
                    "NETWORK_UNAVAILABLE"
                TransientProcessingFailure.reason(
                    NasAccessException("NAS upload failed", IOException("write failed", SocketTimeoutException())),
                ) shouldBe "NETWORK_UNAVAILABLE"
            }

            expect("interruption stays visible through a translated storage failure") {
                ProcessingCancellation.isInterrupted(
                    NasAccessException("NAS upload failed", IOException("interrupted", InterruptedException())),
                ) shouldBe true
            }
        }

        context("public signatures of storage callers") {
            val callers: List<KClass<*>> =
                listOf(
                    NasDestinationResolver::class,
                    ProcessingRetry::class,
                    TransientProcessingFailure::class,
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

            expect("no public member or constructor mentions a jcifs, Spring Resource or web type") {
                val leaks =
                    callers
                        .flatMap { signatures(it) }
                        .filter { "jcifs." in it || "org.springframework.core.io." in it || "org.springframework.http." in it }
                leaks.shouldBeEmpty()
            }

            // The NAS implementation lives in the storage module; processing reaches it only through storage's api package.
            expect("processing sources do not import jcifs") {
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
