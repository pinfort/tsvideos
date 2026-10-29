package me.pinfort.tsvideos.catalog.persistence

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.datatest.withData
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

            context("status") {
                withData(
                    nameFn = { (dto, domain) -> "$dto -> $domain" },
                    CreatedFileDto.Status.REGISTERED to CreatedFile.Status.REGISTERED,
                    CreatedFileDto.Status.ENCODE_SUCCESS to CreatedFile.Status.ENCODE_SUCCESS,
                    CreatedFileDto.Status.FILE_MOVED to CreatedFile.Status.FILE_MOVED,
                ) { (dtoStatus, domainStatus) ->
                    val dto =
                        CreatedFileDto(
                            id = 1,
                            splittedFileId = 2,
                            file = "file.ts",
                            size = 100,
                            mime = null,
                            encoding = null,
                            status = dtoStatus,
                        )

                    dto.toDomain().status shouldBe domainStatus
                }
            }
        }
    })
