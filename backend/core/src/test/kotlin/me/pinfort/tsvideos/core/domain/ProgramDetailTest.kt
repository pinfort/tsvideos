package me.pinfort.tsvideos.core.domain

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class ProgramDetailTest :
    ExpectSpec({
        val programDetail =
            ProgramDetail(
                id = 1,
                name = "name",
                executedFileId = 2,
                status = Program.Status.COMPLETED,
                drops = 0,
                size = 3,
                recordedAt = LocalDateTime.of(2023, 1, 1, 0, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 60.0,
                createdFiles = emptyList(),
            )

        context("detected") {
            expect("true when the tag is attached") {
                programDetail
                    .copy(tags = listOf(ExecutedFileTag.EWS), checks = listOf(ExecutedFileCheck.EMERGENCY_BROADCAST))
                    .detected(ExecutedFileTag.EWS) shouldBe true
            }

            expect("false when the check ran but did not attach the tag") {
                programDetail
                    .copy(checks = listOf(ExecutedFileCheck.EMERGENCY_BROADCAST))
                    .detected(ExecutedFileTag.SUPERIMPOSE) shouldBe false
            }

            expect("null when the check never ran") {
                programDetail.detected(ExecutedFileTag.EWS) shouldBe null
            }
        }
    })
