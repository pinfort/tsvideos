package me.pinfort.tsvideos.core.external.samba

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import me.pinfort.tsvideos.core.config.SambaConfigurationProperties
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/** Exercises actual SMB2 create dispositions and non-replacing rename, not mocked jcifs calls. */
class VerifiedUploadNasTest :
    ExpectSpec({
        val container =
            GenericContainer("ghcr.io/servercontainers/samba:latest")
                .withExposedPorts(445)
                .withEnv("AVAHI_DISABLE", "1")
                .withEnv("WSDD2_DISABLE", "1")
                .withEnv("NETBIOS_DISABLE", "1")
                .withEnv("ACCOUNT_uploadtest", "uploadtest")
                .withEnv("UID_uploadtest", "1000")
                .withEnv("SAMBA_VOLUME_CONFIG_upload", "[upload]; path=/shares; valid users = uploadtest; guest ok = no; read only = no")
                .withTmpFs(mapOf("/shares" to "rw"))
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)))
        lateinit var nas: VerifiedUploadNas
        beforeSpec {
            container.start()
            check(container.execInContainer("sh", "-c", "chown 1000:1000 /shares && chmod 770 /shares").exitCode == 0)
            val server =
                SambaConfigurationProperties.Server(
                    "smb://${container.host}:${container.getMappedPort(445)}/upload",
                    "uploadtest",
                    "uploadtest",
                    "",
                )
            val samba = SambaClient(SambaConfigurationProperties(server, server))
            nas = VerifiedUploadNas(samba, NasComponent(samba, mockk(relaxed = true)))
        }
        afterSpec { container.stop() }
        expect("exclusive transfer never overwrites a pre-existing temporary artifact") {
            val source = Files.createTempFile("smb-upload", ".bin").toFile().apply { writeText("first source") }
            val path = "bucket/${UUID.randomUUID()}/artifact.upload-test"
            val type = SambaClient.NasType.VIDEO_STORE_NAS
            try {
                var owned = false
                nas.transfer(type, path, source, { owned = true }, { _, _ -> })
                owned shouldBe true
                val checksum = nas.sha256(type, path)
                source.writeText("replacement")
                var replacementOwned = false
                shouldThrow<Exception> { nas.transfer(type, path, source, { replacementOwned = true }, { _, _ -> }) }
                replacementOwned shouldBe false
                nas.sha256(type, path) shouldBe checksum
            } finally {
                nas.delete(type, path)
                source.delete()
            }
        }
        expect("verified same-share rename retains bytes and refuses an existing final target") {
            val source = Files.createTempFile("smb-upload", ".bin").toFile().apply { writeBytes(ByteArray(131073) { (it % 251).toByte() }) }
            val directory = "bucket/${UUID.randomUUID()}"
            val temporary = "$directory/artifact.upload-test"
            val final = "$directory/artifact.bin"
            val other = "$directory/other.upload-test"
            val type = SambaClient.NasType.ORIGINAL_STORE_NAS
            try {
                var bytes = 0L
                nas.transfer(type, temporary, source, {}, { value, _ -> bytes = value })
                bytes shouldBe source.length()
                nas.size(type, temporary) shouldBe source.length()
                val checksum = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
                nas.sha256(type, temporary) shouldBe checksum
                nas.finalize(type, temporary, final)
                nas.exists(type, temporary) shouldBe false
                nas.size(type, final) shouldBe source.length()
                nas.sha256(type, final) shouldBe checksum
                nas.transfer(type, other, source, {}, { _, _ -> })
                shouldThrow<IllegalStateException> { nas.finalize(type, other, final) }
                nas.exists(type, other) shouldBe true
                nas.sha256(type, final) shouldBe checksum
            } finally {
                nas.delete(type, temporary)
                nas.delete(type, other)
                nas.delete(type, final)
                source.delete()
            }
        }
    })
