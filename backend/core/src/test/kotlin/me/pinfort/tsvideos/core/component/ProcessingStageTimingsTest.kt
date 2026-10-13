package me.pinfort.tsvideos.core.component

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.domain.ProcessingStage
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProcessingStageTimingsTest :
    ExpectSpec({
        expect("records successful and failed stages in order without leaking after scope") {
            val timer = ProcessingStageTimings()
            val records = mutableListOf<ProcessingStageTiming>()
            shouldThrow<IllegalStateException> {
                timer.observe(records) {
                    timer.measure(ProcessingStage.INSPECT) { 42 } shouldBe 42
                    timer.measure(ProcessingStage.SPLIT) { error("failure") }
                }
            }
            timer.measure(ProcessingStage.COMPRESS) { Unit }
            records.map { it.stage } shouldBe listOf(ProcessingStage.INSPECT, ProcessingStage.SPLIT)
            records.all { it.elapsedMillis >= 0 } shouldBe true
        }
        expect("overlapping workers keep independent stage lists and pooled thread reuse is clean") {
            val timer = ProcessingStageTimings()
            val pool = Executors.newFixedThreadPool(2)
            val entered = CountDownLatch(2)
            try {
                val results =
                    listOf(ProcessingStage.SPLIT, ProcessingStage.UPLOAD_ORIGINAL)
                        .map { stage ->
                            pool.submit(
                                Callable {
                                    val records = mutableListOf<ProcessingStageTiming>()
                                    timer.observe(records) {
                                        entered.countDown()
                                        check(entered.await(5, TimeUnit.SECONDS))
                                        timer.measure(stage) { Unit }
                                    }
                                    timer.measure(ProcessingStage.FINALIZE) { Unit }
                                    records
                                },
                            )
                        }.map { it.get(10, TimeUnit.SECONDS) }
                results.map { it.single().stage } shouldBe listOf(ProcessingStage.SPLIT, ProcessingStage.UPLOAD_ORIGINAL)
            } finally {
                pool.shutdownNow()
            }
        }
        expect("nested scopes restore outer observation after exceptions") {
            val timer = ProcessingStageTimings()
            val outer = mutableListOf<ProcessingStageTiming>()
            val inner = mutableListOf<ProcessingStageTiming>()
            timer.observe(outer) {
                shouldThrow<InterruptedException> {
                    timer.observe(
                        inner,
                    ) { timer.measure(ProcessingStage.SPLIT) { throw InterruptedException() } }
                }
                timer.measure(ProcessingStage.FINALIZE) { Unit }
            }
            outer.single().stage shouldBe ProcessingStage.FINALIZE
            inner.single().stage shouldBe ProcessingStage.SPLIT
        }
    })
