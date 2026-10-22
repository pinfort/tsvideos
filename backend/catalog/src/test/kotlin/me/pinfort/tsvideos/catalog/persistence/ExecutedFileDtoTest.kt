package me.pinfort.tsvideos.catalog.persistence

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import java.time.LocalDateTime

class ExecutedFileDtoTest :
    ExpectSpec({
        context("toDomain") {
            expect("success") {
                val dto =
                    ExecutedFileDto(
                        id = 1,
                        file = "file.ts",
                        drops = 5,
                        size = 1000,
                        recordedAt = LocalDateTime.of(2020, 1, 1, 0, 0, 0),
                        channel = "channel",
                        title = "title",
                        channelName = "channelName",
                        duration = 3600.0,
                        status = ExecutedFileDto.Status.SPLITTED,
                    )

                val actual = dto.toDomain()

                actual shouldBe
                    ExecutedFile(
                        id = 1,
                        file = "file.ts",
                        drops = 5,
                        size = 1000,
                        recordedAt = LocalDateTime.of(2020, 1, 1, 0, 0, 0),
                        channel = "channel",
                        title = "title",
                        channelName = "channelName",
                        duration = 3600.0,
                        status = ExecutedFile.Status.SPLITTED,
                    )
            }

            expect("status REGISTERED") {
                val dto =
                    ExecutedFileDto(
                        id = 1,
                        file = "file.ts",
                        drops = 0,
                        size = 0,
                        recordedAt = LocalDateTime.MIN,
                        channel = "",
                        title = "",
                        channelName = "",
                        duration = 0.0,
                        status = ExecutedFileDto.Status.REGISTERED,
                    )

                dto.toDomain().status shouldBe ExecutedFile.Status.REGISTERED
            }

            expect("status DROPCHECKED") {
                val dto =
                    ExecutedFileDto(
                        id = 1,
                        file = "file.ts",
                        drops = 0,
                        size = 0,
                        recordedAt = LocalDateTime.MIN,
                        channel = "",
                        title = "",
                        channelName = "",
                        duration = 0.0,
                        status = ExecutedFileDto.Status.DROPCHECKED,
                    )

                dto.toDomain().status shouldBe ExecutedFile.Status.DROPCHECKED
            }
        }
    })
