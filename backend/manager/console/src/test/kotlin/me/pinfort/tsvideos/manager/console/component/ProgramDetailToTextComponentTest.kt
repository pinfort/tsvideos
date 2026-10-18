package me.pinfort.tsvideos.manager.console.component

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.ExecutedFileCheck
import me.pinfort.tsvideos.core.domain.ExecutedFileTag
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramDetail
import java.time.LocalDateTime

class ProgramDetailToTextComponentTest :
    ExpectSpec({
        val programDetailToTextComponent = ProgramDetailToTextComponent()

        val programDetail =
            ProgramDetail(
                id = 1,
                name = "name",
                executedFileId = 2,
                status = Program.Status.COMPLETED,
                drops = 2,
                size = 3,
                recordedAt = LocalDateTime.of(2023, 1, 1, 0, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 60.0,
                createdFiles =
                    listOf(
                        CreatedFile(
                            id = 1,
                            mime = "mime",
                            file = "file",
                            splittedFileId = 2,
                            size = 3,
                            encoding = "encoding",
                            status = CreatedFile.Status.FILE_MOVED,
                            width = 1920,
                            height = 1080,
                        ),
                    ),
                tags = listOf(ExecutedFileTag.EWS),
                checks = listOf(ExecutedFileCheck.EMERGENCY_BROADCAST),
            )

        context("convertConsole") {
            expect("success") {
                val actual = programDetailToTextComponent.convertConsole(programDetail)
                val expected =
                    """
                    番組ID: 1
                    番組名: title
                    放送局: channelName
                    放送日時: 2023/01/01 00:00:00
                    放送時間: 1m
                    緊急警報放送: あり
                    文字スーパー: なし
                    ファイル: 1件
                    id	mime	name	resolution
                    1	mime	file	1920x1080

                    """.trimIndent()
                actual shouldBe expected
            }

            expect("shows a dash when the resolution is unknown") {
                val file = programDetail.createdFiles.single().copy(width = null, height = null)
                val actual = programDetailToTextComponent.convertConsole(programDetail.copy(createdFiles = listOf(file)))

                actual.lines() shouldContain "1\tmime\tfile\t-"
            }

            expect("shows unchecked when the recording predates emergency detection") {
                val actual = programDetailToTextComponent.convertConsole(programDetail.copy(tags = emptyList(), checks = emptyList()))

                actual.lines() shouldContain "緊急警報放送: 未検査"
                actual.lines() shouldContain "文字スーパー: 未検査"
            }
        }
    })
