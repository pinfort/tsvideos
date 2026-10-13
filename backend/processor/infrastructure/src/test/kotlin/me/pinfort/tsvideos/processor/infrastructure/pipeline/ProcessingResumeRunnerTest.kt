package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.command.ProcessingRecoveryCommand
import me.pinfort.tsvideos.core.domain.ResumePlan
import me.pinfort.tsvideos.core.domain.ResumeStagePlan

class ProcessingResumeRunnerTest :
    ExpectSpec({
        expect("dry run and blocked plans never acquire ownership or execute either pipeline") {
            val recovery = mockk<ProcessingRecoveryCommand>()
            val pipeline = mockk<FileProcessingPipeline>()
            val callback = mockk<AfterEncodeRunner>()
            val runner = ProcessingResumeRunner(recovery, pipeline, callback)
            val ready = ResumePlan(1, "PROCESS", listOf(ResumeStagePlan("COMPRESS", "SKIP", "Verified")))
            every { recovery.plan(1, null) } returns ready
            runner.run(1, true) shouldBe ready
            val blocked = ready.copy(stages = listOf(ResumeStagePlan("OWNERSHIP", "BLOCKED", "Active owner")))
            every { recovery.plan(1, null) } returns blocked
            runner.run(1, false) shouldBe blocked
            verify { listOf(pipeline, callback) wasNot Called }
            verify(exactly = 0) { recovery.acquire(any(), any()) }
            verify(exactly = 0) { recovery.acquireCallback(any()) }
        }
    })
