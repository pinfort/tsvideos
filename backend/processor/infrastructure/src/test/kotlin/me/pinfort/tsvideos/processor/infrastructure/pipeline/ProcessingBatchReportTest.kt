package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

class ProcessingBatchReportTest :
    ExpectSpec({
        fun report(
            vararg outcomes: BatchOutcome,
            interrupted: Boolean = false,
            complete: Boolean = true,
        ) = ProcessingBatchReport(
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:01:00Z",
            false,
            interrupted,
            complete,
            outcomes.mapIndexed { index, outcome -> RecordingReport(index, "input-$index", outcome, outcome.name, 0, emptyList()) },
        )

        expect("empty and fully successful or skipped batches succeed") {
            report().exitCode shouldBe 0
            report(BatchOutcome.SUBMITTED, BatchOutcome.SKIPPED_ALREADY_REGISTERED, BatchOutcome.DRY_RUN).exitCode shouldBe 0
        }
        expect("failed only returns one and mixed outcomes return four") {
            report(BatchOutcome.FAILED).exitCode shouldBe 1
            report(BatchOutcome.FAILED, BatchOutcome.SUBMITTED).exitCode shouldBe 4
            report(BatchOutcome.FAILED, BatchOutcome.SKIPPED_ALREADY_REGISTERED).exitCode shouldBe 4
        }
        expect("interrupted or incomplete work never reports success even before first input") {
            report(interrupted = true).exitCode shouldBe 130
            report(complete = false).exitCode shouldBe 1
            report(BatchOutcome.SUBMITTED, BatchOutcome.INTERRUPTED).exitCode shouldBe 130
            report(BatchOutcome.NOT_STARTED).exitCode shouldBe 1
        }
    })
