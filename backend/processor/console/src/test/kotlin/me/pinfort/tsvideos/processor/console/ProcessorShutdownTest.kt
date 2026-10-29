package me.pinfort.tsvideos.processor.console

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.processing.api.ProcessingCancellation
import java.util.concurrent.CountDownLatch

class ProcessorShutdownTest :
    ExpectSpec({
        expect("context destruction cannot extend the hook deadline indefinitely") {
            val cancellation = ProcessingCancellation()
            cancellation.runOwned { }
            val release = CountDownLatch(1)
            try {
                val shutdown = ProcessorShutdown(cancellation, 1) { release.await() }
                val start = System.nanoTime()
                shutdown.stop()
                (System.nanoTime() - start < 2_000_000_000L) shouldBe true
            } finally {
                release.countDown()
            }
        }
        context("grace is positive and bounded") {
            withData(nameFn = { "grace=$it rejected" }, 0L, -1L, 301L) {
                shouldThrow<IllegalArgumentException> { ProcessorShutdown(ProcessingCancellation(), it) { } }
            }
        }
    })
