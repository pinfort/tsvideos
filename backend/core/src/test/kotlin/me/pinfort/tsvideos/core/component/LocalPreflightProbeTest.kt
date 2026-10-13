package me.pinfort.tsvideos.core.component

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import java.net.ServerSocket
import java.nio.file.Files

class LocalPreflightProbeTest :
    ExpectSpec({
        expect("real local file checks canonical identity tools and empty directory") {
            val dir = Files.createTempDirectory("preflight-test")
            try {
                val probe = LocalPreflightProbe()
                probe.expand(dir) shouldBe emptyList()
                val file = Files.writeString(dir.resolve("recording.m2ts"), "original")
                probe.expand(dir) shouldBe listOf(file)
                val input = probe.inspect(file)
                input.canonical shouldBe file.toRealPath()
                input.bytes shouldBe 8
                probe.tool(file.toString(), true) shouldBe true
                probe.tool(dir.resolve("missing").toString(), true) shouldBe false
                shouldThrow<IllegalArgumentException> { probe.inspect(dir) }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
        expect("bounded TCP confirms connectivity only and handles refusal") {
            val probe = EncoderConnectivityProbe()
            val port =
                ServerSocket(0).use { listener ->
                    probe.check("127.0.0.1", listener.localPort) shouldBe true
                    listener.localPort
                }
            probe.check("127.0.0.1", port) shouldBe false
        }
        expect("already interrupted TCP does not launch work") {
            try {
                Thread.currentThread().interrupt()
                shouldThrow<InterruptedException> { EncoderConnectivityProbe().check("127.0.0.1", 1) }
            } finally {
                Thread.interrupted()
            }
        }
    })
