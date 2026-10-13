package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.command.ProcessingRecoveryCommand
import me.pinfort.tsvideos.core.domain.ResumePlan
import org.springframework.stereotype.Component

@Component
class ProcessingResumeRunner(
    private val recovery: ProcessingRecoveryCommand,
    private val pipeline: FileProcessingPipeline,
    private val callback: AfterEncodeRunner,
) {
    fun run(
        jobId: Long,
        dryRun: Boolean,
    ): ResumePlan {
        val plan = recovery.plan(jobId)
        if (dryRun || plan.blocked || plan.stages.all { it.action == "SKIP" }) return plan
        when (plan.mode) {
            "PROCESS" -> pipeline.resume(jobId)
            "CALLBACK" -> callback.resume(jobId)
            else -> error("Unsupported recovery mode")
        }
        return plan
    }
}
