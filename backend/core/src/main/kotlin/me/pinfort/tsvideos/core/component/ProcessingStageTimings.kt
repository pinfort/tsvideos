package me.pinfort.tsvideos.core.component

import me.pinfort.tsvideos.core.domain.ProcessingStage
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

data class ProcessingStageTiming(
    val stage: ProcessingStage,
    val elapsedMillis: Long,
)

/** A worker observes only its own stages; finally removes the scope before pooled-thread reuse. */
@Component
class ProcessingStageTimings {
    private val current = ThreadLocal<MutableList<ProcessingStageTiming>>()

    fun <T> observe(
        target: MutableList<ProcessingStageTiming>,
        operation: () -> T,
    ): T {
        val previous = current.get()
        current.set(target)
        try {
            return operation()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun <T> measure(
        stage: ProcessingStage,
        operation: () -> T,
    ): T {
        val started = System.nanoTime()
        try {
            return operation()
        } finally {
            current.get()?.add(ProcessingStageTiming(stage, TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - started).coerceAtLeast(0))))
        }
    }
}
