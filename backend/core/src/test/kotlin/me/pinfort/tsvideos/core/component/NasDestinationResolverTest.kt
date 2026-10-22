package me.pinfort.tsvideos.core.component

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.catalog.api.NormalizeComponent
import me.pinfort.tsvideos.storage.api.NasPaths
import me.pinfort.tsvideos.storage.api.NasStore
import java.nio.file.Path

class NasDestinationResolverTest :
    ExpectSpec({
        expect("normalizes the recording directory and includes the selected NAS base directory") {
            val paths = mockk<NasPaths>()
            val resolver = NasDestinationResolver(NormalizeComponent(), paths)
            for (nasType in NasStore.entries) {
                every { paths.resolvePathUnderBaseDir(nasType, "A/Anime：01/video.mp4") } returns "base/Anime/video.mp4"
                resolver.resolve(Path.of("/recordings/Ａnime:０１"), "video.mp4", nasType) shouldBe "base/Anime/video.mp4"
            }
        }
    })
