package me.pinfort.tsvideos.catalog.persistence

import io.kotest.core.spec.style.ExpectSpec
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

            expect("status COMPRESS_SAVED") {
                val dto =
                    SplittedFileDto(
                        id = 1,
                        executedFileId = 2,
                        file = "test.ts",
                        size = 100,
                        duration = 60.0,
                        status = SplittedFileDto.Status.COMPRESS_SAVED,
                    )

                dto.toDomain().status shouldBe SplittedFile.Status.COMPRESS_SAVED
            }

            expect("status ENCODE_TASK_ADDED") {
                val dto =
                    SplittedFileDto(
                        id = 1,
                        executedFileId = 2,
                        file = "test.ts",
                        size = 100,
                        duration = 60.0,
                        status = SplittedFileDto.Status.ENCODE_TASK_ADDED,
                    )

                dto.toDomain().status shouldBe SplittedFile.Status.ENCODE_TASK_ADDED
            }
        }
    })
