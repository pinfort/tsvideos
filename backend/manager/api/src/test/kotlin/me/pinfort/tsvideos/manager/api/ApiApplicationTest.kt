package me.pinfort.tsvideos.manager.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import me.pinfort.tsvideos.manager.api.stream.ResponseResourcesFilter
import me.pinfort.tsvideos.storage.api.NasComponent
import me.pinfort.tsvideos.storage.api.NasFileVerifier
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

// Regression guard for the class of bug fixed by MyBatisConfig (a bean-wiring mistake,
// e.g. a missing @MapperScan, throws during context startup and fails this test).
// manager:api isn't otherwise built/run by CI (see jar.backend.yaml), so nothing else
// here would catch that.
@SpringBootTest
class ApiApplicationTest : FunSpec() {
    override val extensions = listOf(SpringExtension())

    @Autowired
    lateinit var context: ApplicationContext

    init {
        test("application context loads") {}

        // Video streaming closes its NAS handle through this filter; without it every stream request fails.
        test("the response resource filter is assembled as a servlet filter bean") {
            context.getBeansOfType(ResponseResourcesFilter::class.java).size shouldBe 1
        }

        // The api's own application.yml must name the storage profile; core's application.yaml is not guaranteed to load.
        test("the core and storage profiles and the storage beans are part of the application") {
            context.environment.activeProfiles.toList() shouldContainAll listOf("core", "storage", "api", "infrastructure")
            context.environment.getProperty("samba.video-store-nas.url").shouldNotBeNull() shouldStartWith "smb:"
            context.getBean(NasComponent::class.java)
            context.getBean(NasFileVerifier::class.java)
        }

        // Runtime isolation from processing (#165, module-boundaries.md §2): the context above starts without the processing
        // profile or any processor-* setting, and nothing from processing is loadable, scanned or configured. The build-time
        // side is checkProcessingRuntimeIsolation (processing/runtime-isolation.gradle.kts).
        test("processing is neither on the runtime classpath nor part of the application") {
            ("processing" in context.environment.activeProfiles) shouldBe false
            listOf("processor-tool.ts-splitter-path", "processor-preflight.space-multiplier").forEach {
                context.environment.containsProperty(it) shouldBe false
            }
            val loader = ApiApplication::class.java.classLoader
            listOf(
                "me.pinfort.tsvideos.processing.api.ProcessingPersistence",
                "me.pinfort.tsvideos.processing.api.VerifiedUploadCommand",
                "me.pinfort.tsvideos.processing.api.tool.TsSplitterClient",
            ).forEach { name -> runCatching { Class.forName(name, false, loader) }.isFailure shouldBe true }
            context.beanDefinitionNames
                .mapNotNull { context.getType(it)?.name }
                .filter { it.startsWith("me.pinfort.tsvideos.processing.") }
                .shouldBeEmpty()
        }
    }
}
