package me.pinfort.tsvideos.catalog.persistence

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.catalog.domain.SplittedFile

class SplittedFileDtoTest :
    ExpectSpec({
        context("toDomain") {
            expect("success") {
                val dto =
                    SplittedFileDto(
                        id = 1,
                        executedFileId = 2,
                        file = "test.ts",
                        size = 100,
                        duration = 60.0,
                        status = SplittedFileDto.Status.REGISTERED,
                    )

                val actual = dto.toDomain()

                actual shouldBe
                    SplittedFile(
                        id = 1,
                        executedFileId = 2,
                        file = "test.ts",
                        size = 100,
                        duration = 60.0,
                        status = SplittedFile.Status.REGISTERED,
                    )
            }

            context("status") {
                withData(
                    nameFn = { (dto, domain) -> "$dto -> $domain" },
                    SplittedFileDto.Status.REGISTERED to SplittedFile.Status.REGISTERED,
                    SplittedFileDto.Status.COMPRESS_SAVED to SplittedFile.Status.COMPRESS_SAVED,
                    SplittedFileDto.Status.ENCODE_TASK_ADDED to SplittedFile.Status.ENCODE_TASK_ADDED,
                ) { (dtoStatus, domainStatus) ->
                    val dto =
                        SplittedFileDto(
                            id = 1,
                            executedFileId = 2,
                            file = "test.ts",
                            size = 100,
                            duration = 60.0,
                            status = dtoStatus,
                        )

                    dto.toDomain().status shouldBe domainStatus
                }
            }
        }
    })
