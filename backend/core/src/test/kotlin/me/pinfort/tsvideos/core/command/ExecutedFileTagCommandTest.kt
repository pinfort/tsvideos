package me.pinfort.tsvideos.core.command

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.domain.ExecutedFileCheck
import me.pinfort.tsvideos.core.domain.ExecutedFileTag
import me.pinfort.tsvideos.core.external.database.mapper.ExecutedFileCheckMapper
import me.pinfort.tsvideos.core.external.database.mapper.ExecutedFileTagMapper

class ExecutedFileTagCommandTest :
    ExpectSpec({
        lateinit var executedFileTagMapper: ExecutedFileTagMapper
        lateinit var executedFileCheckMapper: ExecutedFileCheckMapper
        lateinit var executedFileTagCommand: ExecutedFileTagCommand

        beforeTest {
            clearAllMocks()
            executedFileTagMapper = mockk()
            executedFileCheckMapper = mockk()
            executedFileTagCommand = ExecutedFileTagCommand(executedFileTagMapper, executedFileCheckMapper, mockk(relaxed = true))
        }

        context("recordCheck") {
            expect("inserts every tag and the check") {
                every { executedFileTagMapper.insert(any(), any()) } returns 1
                every { executedFileCheckMapper.upsert(any(), any(), any()) } returns 1

                executedFileTagCommand.recordCheck(
                    1,
                    ExecutedFileCheck.EMERGENCY_BROADCAST,
                    setOf(ExecutedFileTag.EWS, ExecutedFileTag.SUPERIMPOSE),
                )

                verify { executedFileTagMapper.insert(1, ExecutedFileTag.EWS) }
                verify { executedFileTagMapper.insert(1, ExecutedFileTag.SUPERIMPOSE) }
                verify { executedFileCheckMapper.upsert(1, ExecutedFileCheck.EMERGENCY_BROADCAST, any()) }
            }

            expect("records the check even when nothing was detected") {
                every { executedFileCheckMapper.upsert(any(), any(), any()) } returns 1

                executedFileTagCommand.recordCheck(1, ExecutedFileCheck.EMERGENCY_BROADCAST, emptySet())

                verify(exactly = 0) { executedFileTagMapper.insert(any(), any()) }
                verify { executedFileCheckMapper.upsert(1, ExecutedFileCheck.EMERGENCY_BROADCAST, any()) }
            }

            expect("dryRun writes nothing") {
                executedFileTagCommand.recordCheck(1, ExecutedFileCheck.EMERGENCY_BROADCAST, setOf(ExecutedFileTag.EWS), dryRun = true)

                verify(exactly = 0) { executedFileTagMapper.insert(any(), any()) }
                verify(exactly = 0) { executedFileCheckMapper.upsert(any(), any(), any()) }
            }
        }

        context("select") {
            expect("returns tags and checks") {
                every { executedFileTagMapper.selectByExecutedFileId(1) } returns listOf(ExecutedFileTag.EWS)
                every { executedFileCheckMapper.selectByExecutedFileId(1) } returns listOf(ExecutedFileCheck.EMERGENCY_BROADCAST)

                executedFileTagCommand.selectTags(1) shouldBe listOf(ExecutedFileTag.EWS)
                executedFileTagCommand.selectChecks(1) shouldBe listOf(ExecutedFileCheck.EMERGENCY_BROADCAST)
            }
        }

        context("deleteByExecutedFileId") {
            expect("deletes tags and checks") {
                every { executedFileTagMapper.deleteByExecutedFileId(1) } returns 1
                every { executedFileCheckMapper.deleteByExecutedFileId(1) } returns 1

                executedFileTagCommand.deleteByExecutedFileId(1)

                verify { executedFileTagMapper.deleteByExecutedFileId(1) }
                verify { executedFileCheckMapper.deleteByExecutedFileId(1) }
            }

            expect("dryRun deletes nothing") {
                executedFileTagCommand.deleteByExecutedFileId(1, dryRun = true)

                verify(exactly = 0) { executedFileTagMapper.deleteByExecutedFileId(any()) }
                verify(exactly = 0) { executedFileCheckMapper.deleteByExecutedFileId(any()) }
            }
        }
    })
