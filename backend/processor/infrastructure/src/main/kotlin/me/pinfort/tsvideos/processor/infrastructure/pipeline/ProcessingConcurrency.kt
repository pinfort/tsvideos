package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.component.ProcessingCancellation
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Component
class ProcessingConcurrency(
    @Value("\${processor.parallel.workers:1}") val workers: Int = 1,
    @Value("\${processor.parallel.queue-capacity:0}") queueCapacity: Int = 0,
    @Value("\${processor.parallel.split:1}") split: Int = 1,
    @Value("\${processor.parallel.compress:1}") compress: Int = 1,
    @Value("\${processor.parallel.upload:1}") upload: Int = 1,
    @Value("\${processor.parallel.encoder-submit:1}") encoderSubmit: Int = 1,
) {
    val queueCapacity = if (queueCapacity == 0) workers * 2 else queueCapacity
    private val permits =
        mapOf(
            "SPLIT" to Semaphore(split, true),
            "COMPRESS" to Semaphore(compress, true),
            "UPLOAD" to Semaphore(upload, true),
            "ENCODER_SUBMIT" to Semaphore(encoderSubmit, true),
        )

    init {
        require(listOf(workers, split, compress, upload, encoderSubmit).all { it in 1..32 }) { "Parallel limits must be 1..32" }
        require(this.queueCapacity in 1..1024) { "Queue capacity must be 1..1024 (0 selects twice workers)" }
    }

    fun <T> stage(
        name: String,
        cancellation: ProcessingCancellation,
        operation: () -> T,
    ): T {
        cancellation.check()
        val semaphore = permits.getValue(name)
        semaphore.acquire()
        try {
            cancellation.check()
            return operation()
        } finally {
            semaphore.release()
        }
    }

    /** No futures retained and no caller-runs bypass: producer waits when the bounded queue is full. */
    fun executor(cancellation: ProcessingCancellation): ThreadPoolExecutor =
        ThreadPoolExecutor(
            workers,
            workers,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            { runnable -> Thread(runnable, "recording-worker").apply { isDaemon = true } },
            { task, executor ->
                cancellation.check()
                check(!executor.isShutdown) { "Executor is closed" }
                executor.queue.put(task)
                cancellation.check()
            },
        )
}
