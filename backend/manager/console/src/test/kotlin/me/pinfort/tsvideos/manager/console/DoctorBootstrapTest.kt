package me.pinfort.tsvideos.manager.console

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class DoctorBootstrapTest :
    ExpectSpec({
        expect("loads the actual application core profile defaults without any application context") {
            val environment = DoctorBootstrap.loadEnvironment(emptyArray())
            environment.activeProfiles.toList().contains("core") shouldBe true
            environment.getProperty("spring.datasource.url").shouldNotBeNull() shouldContain "jdbc:"
            environment.getProperty("samba.video-store-nas.url").shouldNotBeNull() shouldContain "smb:"
        }
        expect("external profile configuration overrides normal core defaults") {
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
