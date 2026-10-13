package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.component.ProcessingStageTiming

enum class BatchOutcome { SUBMITTED, SKIPPED_ALREADY_REGISTERED, DRY_RUN, FAILED, INTERRUPTED, NOT_STARTED }

enum class BatchNotification { PER_FILE, SUMMARY, NONE }

data class RecordingReport(
    val ordinal: Int,
    val path: String,
    val outcome: BatchOutcome,
    val reasonCode: String,
    val elapsedMillis: Long,
    val stageTimings: List<ProcessingStageTiming>,
)

data class ProcessingBatchReport(
    val startedAt: String,
    val endedAt: String,
    val dryRun: Boolean,
    val interrupted: Boolean,
    val completeDiscovery: Boolean,
    val recordings: List<RecordingReport>,
) {
    val submitted: Int get() = recordings.count { it.outcome == BatchOutcome.SUBMITTED }
    val skipped: Int get() = recordings.count { it.outcome == BatchOutcome.SKIPPED_ALREADY_REGISTERED }
    val planned: Int get() = recordings.count { it.outcome == BatchOutcome.DRY_RUN }
    val failed: Int get() = recordings.count { it.outcome == BatchOutcome.FAILED }
    val notStarted: Int get() = recordings.count { it.outcome == BatchOutcome.NOT_STARTED }
    val interruptedRecordings: Int get() = recordings.count { it.outcome == BatchOutcome.INTERRUPTED }
    val exitCode: Int
        get() =
            when {
                interrupted || interruptedRecordings > 0 -> 130
                !completeDiscovery || notStarted > 0 -> 1
                failed == 0 -> 0
                submitted + skipped + planned > 0 -> 4
                else -> 1
            }
}
