package me.pinfort.tsvideos.manager.console

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import me.pinfort.tsvideos.storage.api.NasComponent
import me.pinfort.tsvideos.storage.api.NasFileVerifier
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.nio.file.Files

class DoctorBootstrapTest :
    ExpectSpec({
        expect("loads the actual application datasource and storage profile defaults without any application context") {
            val environment = DoctorBootstrap.loadEnvironment(emptyArray())
            // spring.datasource.* lives in this console's own application-datasource.yaml (core's until #139)
            // samba.* defaults live in the storage module's application-storage.yaml
            environment.activeProfiles.toList() shouldContainAll listOf("datasource", "storage")
            environment.getProperty("spring.datasource.url").shouldNotBeNull() shouldContain "jdbc:"
            environment.getProperty("spring.datasource.hikari.auto-commit") shouldBe "false"
            environment.getProperty("samba.video-store-nas.url").shouldNotBeNull() shouldContain "smb:"
        }
        // ConsoleApplication scans storage; its beans must assemble from this console's own configuration alone.
        expect("the storage beans assemble from the console's loaded configuration") {
            AnnotationConfigApplicationContext()
                .apply {
                    environment = DoctorBootstrap.loadEnvironment(emptyArray())
                    scan("me.pinfort.tsvideos.storage")
                    refresh()
                }.use { context ->
                    context.getBean(NasComponent::class.java)
                    context.getBean(NasFileVerifier::class.java)
                }
        }
        expect("external profile configuration overrides the packaged defaults") {
            val directory = Files.createTempDirectory("doctor-config-")
            try {
                Files.writeString(directory.resolve("application-doctor-test.properties"), "samba.video-store-nas.base-dir=custom-base\n")
                val environment =
                    DoctorBootstrap.loadEnvironment(
                        arrayOf("--spring.profiles.active=doctor-test", "--spring.config.additional-location=${directory.toUri()}"),
                    )
                environment.getProperty("samba.video-store-nas.base-dir") shouldBe "custom-base"
            } finally {
                Files.deleteIfExists(directory.resolve("application-doctor-test.properties"))
                Files.delete(directory)
            }
        }
        expect("Spring options before or after doctor cannot bypass its diagnostic startup route") {
            DoctorBootstrap.commandArguments(arrayOf("--spring.profiles.active=local", "doctor", "--json")) shouldBe
                listOf("doctor", "--json")
            DoctorBootstrap.commandArguments(arrayOf("doctor", "--spring.profiles.active=local", "--json")) shouldBe
                listOf("doctor", "--json")
            DoctorBootstrap.commandArguments(arrayOf("search", "--name", "doctor")) shouldBe listOf("search", "--name", "doctor")
        }
    })
