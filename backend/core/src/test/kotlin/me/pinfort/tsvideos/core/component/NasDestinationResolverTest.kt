package me.pinfort.tsvideos.core.component

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.external.samba.SambaClient
import java.nio.file.Path

class NasDestinationResolverTest :
    ExpectSpec({
        expect("normalizes the recording directory and includes the selected NAS base directory") {
            val samba = mockk<SambaClient>()
            val resolver = NasDestinationResolver(NormalizeComponent(), samba)
            for (nasType in SambaClient.NasType.entries) {
                every { samba.resolvePathUnderBaseDir(nasType, "A/Anime：01/video.mp4") } returns "base/Anime/video.mp4"
                resolver.resolve(Path.of("/recordings/Ａnime:０１"), "video.mp4", nasType) shouldBe "base/Anime/video.mp4"
            }
        }
    })
