package me.pinfort.tsvideos.processing.domain

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

class ProcessingIdentityTest :
    ExpectSpec({
        expect("canonical aliases share identity but changed content size is a new version") {
            val dir = Files.createTempDirectory("identity").toFile()
            try {
                val file = dir.resolve("recording.m2ts").apply { writeText("first") }
                val first = ProcessingIdentity.from(file)
                ProcessingIdentity.from(dir.resolve("./recording.m2ts")) shouldBe first
                file.writeText("changed version")
                ProcessingIdentity.from(file).key shouldNotBe first.key
            } finally {
                dir.deleteRecursively()
            }
        }
        expect("last modified distinguishes same-sized versions") {
            val file = Files.createTempFile("identity", ".m2ts").toFile()
            try {
                file.writeText("first")
                val first = ProcessingIdentity.from(file)
                file.setLastModified(first.modifiedMillis + 10000) shouldBe true
                ProcessingIdentity.from(file).key shouldNotBe first.key
            } finally {
                file.delete()
            }
        }
    })
