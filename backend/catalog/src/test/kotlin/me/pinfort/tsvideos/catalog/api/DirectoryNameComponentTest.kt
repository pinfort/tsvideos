package me.pinfort.tsvideos.catalog.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path

class DirectoryNameComponentTest :
    ExpectSpec({
        val normalizeComponent = mockk<NormalizeComponent>()
        val directoryNameComponent = DirectoryNameComponent(normalizeComponent)

        context("indexDirectoryName") {
            expect("success") {
                every { normalizeComponent.normalize("かきくけこ") } returns "さしすせそ"

                val result = directoryNameComponent.indexDirectoryName(Path.of("あいうえお/かきくけこ/てすとvideofile.m2ts"))

                result shouldBe "さ"
            }
        }

        context("programDirectoryName") {
            expect("success") {
                every { normalizeComponent.normalize("かきくけこ") } returns "さしすせそ"

                val result = directoryNameComponent.programDirectoryName(Path.of("あいうえお/かきくけこ/てすとvideofile.m2ts"))

                result shouldBe "さしすせそ"
            }
        }

        context("replaceWithGivenDirectoryName") {
            expect("success") {
                every { normalizeComponent.normalize("ほげ") } returns "ほげ"

                val result = directoryNameComponent.replaceWithGivenDirectoryName(Path.of("あいうえお/かきくけこ/さしすせそ/てすとvideofile.m2ts"), "ほげ")

                result shouldBe Path.of("あいうえお/ほ/ほげ/てすとvideofile.m2ts")
            }
        }
    })
