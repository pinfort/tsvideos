package me.pinfort.tsvideos.storage.samba

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.storage.api.NasStore
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

class NasMoveIntegrationTest :
    ExpectSpec({
        expect("real Samba exclusive copy refuses occupied target and verifies successful move") {
            GenericContainer(DockerImageName.parse("ghcr.io/servercontainers/samba:latest"))
                .apply {
                    withEnv("AVAHI_DISABLE", "1")
                    withEnv("WSDD2_DISABLE", "1")
                    withEnv("NETBIOS_DISABLE", "1")
                    withEnv("ACCOUNT_test", "test-password")
                    withEnv("UID_test", "1000")
                    withEnv(
                        "SAMBA_VOLUME_CONFIG_test",
                        "[test]; path=/shares/test; valid users = test; guest ok = no; read only = no; browseable = yes",
                    )
                    withExposedPorts(445)
                    waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)))
                }.use { container ->
                    container.start()
                    val setup =
                        container.execInContainer(
                            "sh",
                            "-c",
                            "mkdir -p /shares/test/base/a/alpha /shares/test/base/b/beta; chown -R 1000 /shares/test",
                        )
                    setup.exitCode shouldBe 0
                    val server =
                        SambaConfigurationProperties.Server(
                            "smb://${container.host}:${container.getMappedPort(445)}/test/",
                            "test",
                            "test-password",
                            "base",
                        )
                    val client = SambaClient(SambaConfigurationProperties(server, server))
                    val nas = SambaNasComponent(client, LoggerFactory.getLogger("NasMoveIntegrationTest"))
                    val old = "base/a/alpha/video.mp4"
                    val destination = "base/b/beta/video.mp4"
                    client.videoStoreNas().use { root ->
                        root.resolve(old).use { it.openOutputStream().use { out -> out.write(byteArrayOf(1, 2, 3, 4)) } }
                        root.resolve(destination).use { it.openOutputStream().use { out -> out.write(byteArrayOf(9, 8)) } }
                        shouldThrowAny { nas.moveResource(old, destination) }
                        shouldThrowAny { nas.deleteResource("base/a/alpha") }
                        root.resolve(old).use {
                            it.openInputStream().use { input ->
                                input.readBytes().toList() shouldBe
                                    listOf<Byte>(1, 2, 3, 4)
                            }
                        }
                        root.resolve(destination).use {
                            it.openInputStream().use { input -> input.readBytes().toList() shouldBe listOf<Byte>(9, 8) }
                            it.delete()
                        }
                        nas.moveResource(old, destination) shouldBe NasStore.VIDEO_STORE_NAS
                        root.resolve(old).use { it.exists() shouldBe false }
                        root.resolve(destination).use {
                            it.openInputStream().use { input ->
                                input.readBytes().toList() shouldBe
                                    listOf<Byte>(1, 2, 3, 4)
                            }
                        }
                    }
                }
        }
    })
