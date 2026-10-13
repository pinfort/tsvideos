package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProcessingConcurrencyTest :
    ExpectSpec({
        fun awaitWaiting(thread: Thread) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
            thread.state shouldBe Thread.State.WAITING
        }
        listOf("SPLIT", "COMPRESS", "UPLOAD", "ENCODER_SUBMIT").forEach { stage ->
            expect("$stage has an independent configured limit and releases after failure") {
                val limits = ProcessingConcurrency(split = 2, compress = 2, upload = 2, encoderSubmit = 2)
                val cancellation = ProcessingCancellation()
                val entered = CountDownLatch(2)
                val release = CountDownLatch(1)
                val count = AtomicInteger()
                val threads =
                    (1..3).map {
                        Thread {
                            limits.stage(stage, cancellation) {
                                count.incrementAndGet()
                                entered.countDown()
                                release.await()
                            }
                        }.apply { start() }
                    }
                try {
                    entered.await(5, TimeUnit.SECONDS) shouldBe true
                    threads.forEach { awaitWaiting(it) }
                    count.get() shouldBe 2
                    // A saturated stage does not consume another stage's permits.
                    limits.stage(if (stage == "SPLIT") "UPLOAD" else "SPLIT", cancellation) { }
                } finally {
                    release.countDown()
                    threads.forEach { it.join(5000) }
                }
                count.get() shouldBe 3
                shouldThrow<IllegalStateException> { limits.stage(stage, cancellation) { error("stage failed") } }
                limits.stage(stage, cancellation) { } // no leaked permit
            }
        }
        expect("bounded queue blocks producer and never executes on caller") {
            val limits = ProcessingConcurrency(workers = 1, queueCapacity = 1)
            val executor = limits.executor(ProcessingCancellation())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val done = CountDownLatch(2)
            val thirdRan = AtomicInteger()
            executor.execute {
                entered.countDown()
                release.await()
            }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            executor.execute { done.countDown() }
            val producer =
                Thread {
                    executor.execute {
                        thirdRan.incrementAndGet()
                        done.countDown()
                    }
                }.apply { start() }
            try {
                awaitWaiting(producer)
                executor.queue.size shouldBe 1
                executor.activeCount shouldBe 1
                thirdRan.get() shouldBe 0
            } finally {
                release.countDown()
                producer.join(5000)
                executor.shutdown()
            }
            done.await(5, TimeUnit.SECONDS) shouldBe true
            executor.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
        }
        expect("invalid resource limits are rejected") {
            shouldThrow<IllegalArgumentException> { ProcessingConcurrency(workers = 0) }
            shouldThrow<IllegalArgumentException> { ProcessingConcurrency(upload = 33) }
            shouldThrow<IllegalArgumentException> { ProcessingConcurrency(queueCapacity = 1025) }
        }
    })
