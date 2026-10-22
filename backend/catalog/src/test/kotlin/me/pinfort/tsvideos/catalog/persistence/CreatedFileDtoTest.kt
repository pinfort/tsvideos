package me.pinfort.tsvideos.catalog.persistence

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.catalog.domain.CreatedFile

class CreatedFileDtoTest :
    ExpectSpec({
        context("toDomain") {
            expect("success") {
                val dto =
                    CreatedFileDto(
                        id = 1,
                        splittedFileId = 2,
                        file = "file.ts",
                        size = 100,
                        mime = "video/vnd.dlna.mpeg-tts",
                        encoding = "encoding",
                        status = CreatedFileDto.Status.ENCODE_SUCCESS,
                    )

                val actual = dto.toDomain()

                actual shouldBe
                    CreatedFile(
                        id = 1,
                        splittedFileId = 2,
                        file = "file.ts",
                        size = 100,
                        mime = "video/vnd.dlna.mpeg-tts",
                        encoding = "encoding",
                        status = CreatedFile.Status.ENCODE_SUCCESS,
                    )
            }

            expect("status REGISTERED") {
                val dto =
                    CreatedFileDto(
                        id = 1,
                        splittedFileId = 2,
                        file = "file.ts",
                        size = 100,
                        mime = null,
                        encoding = null,
                        status = CreatedFileDto.Status.REGISTERED,
                    )

                dto.toDomain().status shouldBe CreatedFile.Status.REGISTERED
            }

            expect("status FILE_MOVED") {
                val dto =
                    CreatedFileDto(
                        id = 1,
                        splittedFileId = 2,
                        file = "file.ts",
                        size = 100,
                        mime = null,
                        encoding = null,
                        status = CreatedFileDto.Status.FILE_MOVED,
                    )

                dto.toDomain().status shouldBe CreatedFile.Status.FILE_MOVED
            }
        }
    })
