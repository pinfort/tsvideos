package me.pinfort.tsvideos.processor.console

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.component.ProcessingCancellation
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
        expect("grace is positive and bounded") {
            listOf(0L, -1L, 301L).forEach {
                shouldThrow<IllegalArgumentException> { ProcessorShutdown(ProcessingCancellation(), it) { } }
            }
        }
    })
