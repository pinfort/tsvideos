package me.pinfort.tsvideos.catalog.persistence

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.ProgramDetail
import java.time.LocalDateTime

class ProgramDtoTest :
    ExpectSpec({
        context("toDomain") {
            expect("success with all fields") {
                val dto =
                    ProgramDto(
                        id = 1,
                        name = "name",
                        executedFileId = 2,
                        status = ProgramDto.Status.COMPLETED,
                        drops = 3,
                        size = 4,
                        recordedAt = LocalDateTime.of(2020, 1, 1, 0, 0, 0),
                        channel = "channel",
                        title = "title",
                        channelName = "channelName",
                        duration = 5.0,
                    )

                val actual = dto.toDomain()

                actual shouldBe
                    Program(
                        id = 1,
                        name = "name",
                        executedFileId = 2,
                        status = Program.Status.COMPLETED,
                        drops = 3,
                        size = 4,
                        recordedAt = LocalDateTime.of(2020, 1, 1, 0, 0, 0),
                        channel = "channel",
                        title = "title",
                        channelName = "channelName",
                        duration = 5.0,
                    )
            }

            expect("success with null values") {
                val dto =
                    ProgramDto(
                        id = 1,
                        name = "name",
                        executedFileId = 2,
                        status = ProgramDto.Status.REGISTERED,
                        drops = null,
                        size = null,
                        recordedAt = null,
                        channel = null,
                        title = null,
                        channelName = null,
                        duration = null,
                    )

                val actual = dto.toDomain()

                actual shouldBe
                    Program(
                        id = 1,
                        name = "name",
                        executedFileId = 2,
                        status = Program.Status.REGISTERED,
                        drops = -1,
                        size = 0,
                        recordedAt = LocalDateTime.MIN,
                        channel = "",
                        title = "",
                        channelName = "",
                        duration = -1.0,
                    )
            }

            expect("status REGISTERED") {
                val dto =
                    ProgramDto(
                        id = 1,
                        name = "name",
                        executedFileId = 2,
                        status = ProgramDto.Status.REGISTERED,
                        drops = null,
                        size = null,
                        recordedAt = null,
                        channel = null,
                        title = null,
                        channelName = null,
                        duration = null,
                    )

                dto.toDomain().status shouldBe Program.Status.REGISTERED
            }

            expect("status ERROR") {
                val dto =
                    ProgramDto(
                        id = 1,
                        name = "name",
                        executedFileId = 2,
                        status = ProgramDto.Status.ERROR,
                        drops = null,
                        size = null,
                        recordedAt = null,
                        channel = null,
                        title = null,
                        channelName = null,
                        duration = null,
                    )

                dto.toDomain().status shouldBe Program.Status.ERROR
            }
        }

        context("toProgramDetail") {
            expect("success") {
                val programDto =
                    ProgramDto(
                        id = 1,
                        name = "name",
                        executedFileId = 2,
                        status = ProgramDto.Status.COMPLETED,
                        drops = 3,
                        size = 4,
                        recordedAt = LocalDateTime.of(2020, 1, 1, 0, 0, 0),
                        channel = "channel",
                        title = "title",
                        channelName = "channelName",
                        duration = 5.0,
                    )
                val createdFiles =
                    listOf(
                        CreatedFileDto(
                            id = 1,
                            splittedFileId = 2,
                            file = "file1.ts",
                            size = 100,
                            mime = "video/vnd.dlna.mpeg-tts",
                            encoding = "encoding",
                            status = CreatedFileDto.Status.ENCODE_SUCCESS,
                        ),
                        CreatedFileDto(
                            id = 2,
                            splittedFileId = 2,
                            file = "file2.mp4",
                            size = 200,
                            mime = "video/mp4",
                            encoding = "encoding",
                            status = CreatedFileDto.Status.FILE_MOVED,
                        ),
                    )

                val actual = programDto.toProgramDetail(createdFiles)

                actual shouldBe
                    ProgramDetail(
                        id = 1,
                        name = "name",
                        executedFileId = 2,
                        status = Program.Status.COMPLETED,
                        drops = 3,
                        size = 4,
                        recordedAt = LocalDateTime.of(2020, 1, 1, 0, 0, 0),
                        channel = "channel",
                        title = "title",
                        channelName = "channelName",
                        duration = 5.0,
                        createdFiles =
                            listOf(
                                CreatedFile(
                                    id = 1,
                                    splittedFileId = 2,
                                    file = "file1.ts",
                                    size = 100,
                                    mime = "video/vnd.dlna.mpeg-tts",
                                    encoding = "encoding",
                                    status = CreatedFile.Status.ENCODE_SUCCESS,
                                ),
                                CreatedFile(
                                    id = 2,
                                    splittedFileId = 2,
                                    file = "file2.mp4",
                                    size = 200,
                                    mime = "video/mp4",
                                    encoding = "encoding",
                                    status = CreatedFile.Status.FILE_MOVED,
                                ),
                            ),
                    )
            }

            expect("success with empty createdFiles") {
                val programDto =
                    ProgramDto(
                        id = 1,
                        name = "name",
                        executedFileId = 2,
                        status = ProgramDto.Status.COMPLETED,
                        drops = null,
                        size = null,
                        recordedAt = null,
                        channel = null,
                        title = null,
                        channelName = null,
                        duration = null,
                    )

                val actual = programDto.toProgramDetail(emptyList())

                actual shouldBe
                    ProgramDetail(
                        id = 1,
                        name = "name",
                        executedFileId = 2,
                        status = Program.Status.COMPLETED,
                        drops = -1,
                        size = 0,
                        recordedAt = LocalDateTime.MIN,
                        channel = "",
                        title = "",
                        channelName = "",
                        duration = -1.0,
                        createdFiles = emptyList(),
                    )
            }
        }
    })
