package me.pinfort.tsvideos.storage.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.Logger
import org.springframework.boot.Banner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import java.io.File
import java.io.IOException
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility

class StorageContractTest :
    ExpectSpec({
        context("NasStore compatibility") {
            expect("keeps the persisted constant names and order of the former SambaClient.NasType") {
                NasStore.entries.map { it.name } shouldContainExactly listOf("VIDEO_STORE_NAS", "ORIGINAL_STORE_NAS")
                NasStore.valueOf("VIDEO_STORE_NAS") shouldBe NasStore.VIDEO_STORE_NAS
                NasStore.valueOf("ORIGINAL_STORE_NAS") shouldBe NasStore.ORIGINAL_STORE_NAS
            }
        }

        context("NasFileVerifier.Status") {
            // Callers map each value to their own status of the same name (persisted health findings, verify output).
            expect("keeps the low-level check results") {
                NasFileVerifier.Status.entries.map { it.name } shouldContainExactly
                    listOf("VERIFIED", "MISSING", "SIZE_MISMATCH", "INACCESSIBLE", "UNCHECKED")
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
        }

        context("public contracts") {
            val contracts: List<KClass<*>> =
                listOf(
                    NasStore::class,
                    NasStorageException::class,
                    NasAccessException::class,
                    NasAuthenticationException::class,
                    NasReadHandle::class,
                    NasComponent::class,
                    NasPaths::class,
                    VerifiedUploadNas::class,
                    NasFileVerifier::class,
                    NasFileVerifier.Result::class,
                    NasFileVerifier.Session::class,
                    DiagnosticNasClient::class,
                    DiagnosticNasClient.Connection::class,
                    DiagnosticNasClient.Directory::class,
                    DiagnosticNasClient.FileHandle::class,
                    DiagnosticNasClient.Companion::class,
                )

            fun signatures(type: KClass<*>): List<String> {
                val members = type.members.filter { it.visibility == KVisibility.PUBLIC }
                val constructors = type.constructors.filter { it.visibility == KVisibility.PUBLIC }
                return members.flatMap { member ->
                    (member.parameters.map { it.type } + member.returnType).map { "${type.simpleName}.${member.name}: $it" }
                } +
                    constructors.flatMap { constructor -> constructor.parameters.map { "${type.simpleName}(): ${it.type}" } }
            }

            expect("no public member or constructor mentions a jcifs, Samba implementation, Spring Resource or web type") {
                val leaks =
                    contracts.flatMap { signatures(it) }.filter {
                        "jcifs." in it ||
                            "me.pinfort.tsvideos.storage.samba." in it ||
                            "org.springframework.core.io." in it ||
                            "org.springframework.http." in it
                    }
                leaks.shouldBeEmpty()
            }

            // The api package holds only implementation-independent declarations; jcifs stays in the samba package.
            expect("jcifs is imported only by the Samba implementation") {
                val root = File("src/main/kotlin")
                root.isDirectory shouldBe true
                val offenders =
                    root
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .filter { file -> file.readLines().any { it.startsWith("import jcifs.") } }
                        .map { it.relativeTo(root).invariantSeparatorsPath }
                        .filterNot { it.startsWith("me/pinfort/tsvideos/storage/samba/") }
                        .toList()
                offenders.shouldBeEmpty()
            }

            // Top-level declarations without a visibility modifier would be public API of the implementation.
            expect("every top-level Samba implementation declaration is internal to the storage module") {
                val root = File("src/main/kotlin/me/pinfort/tsvideos/storage/samba")
                val publicDeclaration = Regex("""^(open |abstract |data |enum |inline )*(class|object|interface|fun|val|var) """)
                val publicDeclarations =
                    root
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .flatMap { file -> file.readLines().filter { publicDeclaration.containsMatchIn(it) }.map { "${file.name}: $it" } }
                        .toList()
                publicDeclarations.shouldBeEmpty()
            }
        }

        // Assembled by Spring Boot itself from storage alone: the `storage` profile loads the packaged
        // application-storage.yaml, and nothing from an application (e.g. its Logger bean) is on the classpath.
        context("Spring assembly") {
            fun storageApplication(vararg profiles: String): SpringApplicationBuilder =
                SpringApplicationBuilder(StorageOnlyConfiguration::class.java)
                    .web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF)
                    .logStartupInfo(false)
                    .profiles(*profiles)

            // Command-line arguments, so they override the profile file like an operator's settings do.
            fun storageContext(properties: Map<String, Any>): ConfigurableApplicationContext =
                storageApplication("storage").run(*properties.map { (key, value) -> "--$key=$value" }.toTypedArray())

            expect("scanning the storage package with the packaged defaults provides every public contract") {
                storageContext(emptyMap()).use { context ->
                    context.getBean(NasComponent::class.java)
                    context.getBean(VerifiedUploadNas::class.java)
                    context.getBean(NasFileVerifier::class.java)
                    context.getBean(DiagnosticNasClient::class.java)
                    context.getBean(NasPaths::class.java)
                    context.environment.getProperty("samba.video-store-nas.url")!! shouldStartWith "smb:"
                    context.environment.getProperty("samba.original-store-nas.url")!! shouldStartWith "smb:"
                }
            }

            expect("storage needs no Logger or other bean from outside the module") {
                storageContext(emptyMap()).use { context ->
                    context.getBeanNamesForType(Logger::class.java).toList().shouldBeEmpty()
                    val foreign =
                        context.beanDefinitionNames
                            .mapNotNull { context.getType(it)?.name }
                            .filter { it.startsWith("me.pinfort.tsvideos.") && !it.startsWith("me.pinfort.tsvideos.storage.") }
                    foreign.shouldBeEmpty()
                }
            }

            expect("without the storage profile the packaged NAS settings are absent and assembly fails") {
                shouldThrow<Exception> { storageApplication().run().close() }
            }

            expect("samba.* settings are bound, keeping the base directory in persisted paths") {
                val settings =
                    mapOf(
                        "samba.video-store-nas.base-dir" to "/video-base/",
                        "samba.original-store-nas.base-dir" to "",
                    )
                storageContext(settings).use { context ->
                    val paths = context.getBean(NasPaths::class.java)
                    paths.resolvePathUnderBaseDir(NasStore.VIDEO_STORE_NAS, "a/alpha/file.mp4") shouldBe "video-base/a/alpha/file.mp4"
                    paths.resolvePathUnderBaseDir(NasStore.ORIGINAL_STORE_NAS, "a/alpha/file.m2ts") shouldBe "a/alpha/file.m2ts"
                }
            }

            expect("the diagnostic client is also available without Spring") {
                DiagnosticNasClient.create().shouldBeInstanceOf<DiagnosticNasClient>()
            }
        }
    })

/** What an application declares for storage: a component scan of the storage package, nothing else. */
@Configuration
@ComponentScan("me.pinfort.tsvideos.storage")
class StorageOnlyConfiguration
