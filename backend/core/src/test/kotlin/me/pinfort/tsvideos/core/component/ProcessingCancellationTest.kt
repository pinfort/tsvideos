package me.pinfort.tsvideos.core.component

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProcessingCancellationTest :
    ExpectSpec({
        expect("stop before admission prevents work") {
            val cancellation = ProcessingCancellation()
            cancellation.requestStop()
            var ran = false
            shouldThrow<InterruptedException> { cancellation.runOwned { ran = true } }
            ran shouldBe false
            cancellation.awaitStopped(1) shouldBe true
        }
        expect("stop without an owner releases the shutdown wait at once") {
            val cancellation = ProcessingCancellation()
            cancellation.requestStop()
            cancellation.awaitStopped(0) shouldBe true
        }
        expect("one owner only and bounded wait while uncooperative work remains") {
            val cancellation = ProcessingCancellation()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val owner =
                Thread {
                    try {
                        cancellation.runOwned {
                            entered.countDown()
                            while (release.count > 0) {
                                Thread.interrupted()
                                Thread.onSpinWait()
                            }
                        }
                    } catch (_: InterruptedException) {
                    }
                }
            owner.start()
            try {
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                shouldThrow<IllegalStateException> { cancellation.runOwned { } }
                cancellation.requestStop()
                cancellation.awaitStopped(10) shouldBe false
            } finally {
                release.countDown()
                owner.join(5000)
            }
            cancellation.awaitStopped(10) shouldBe true
        }
        expect("recovery temporarily clears and restores interrupted status") {
            try {
                Thread.currentThread().interrupt()
                ProcessingCancellation.recovery { Thread.currentThread().isInterrupted shouldBe false }
                Thread.currentThread().isInterrupted shouldBe true
            } finally {
                Thread.interrupted()
            }
        }
    })
