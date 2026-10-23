package me.pinfort.tsvideos.processing.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class RecordingInputsTest :
    ExpectSpec({
        expect("directories yield regular .m2ts children only, non-recursively and in filename order") {
            val dir = Files.createTempDirectory("recording-inputs")
            try {
                val b = Files.writeString(dir.resolve("b.m2ts"), "")
                val a = Files.writeString(dir.resolve("a.m2ts"), "")
                Files.writeString(dir.resolve("upper.M2TS"), "")
                Files.writeString(dir.resolve("note.txt"), "")
                Files.createDirectory(dir.resolve("folder.m2ts"))
                Files.writeString(Files.createDirectory(dir.resolve("nested")).resolve("c.m2ts"), "")
                expandRecordingInputs(dir) shouldBe listOf(a, b)
                expandRecordingInputs(a) shouldBe listOf(a)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
