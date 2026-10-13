package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import java.nio.file.Path

class WatchReadinessTest :
    ExpectSpec({
        val input = ProcessingIdentity("one", "/recording", 10, 20)
        expect("stability requires separate observations and the complete monotonic interval") {
            val tracker = WatchReadiness(10)
            tracker.ready(input, 0, 1, 100, false) shouldBe false
            tracker.ready(input, 200, 1, 100, false) shouldBe false
            tracker.ready(input, 99, 2, 100, false) shouldBe false
            tracker.ready(input, 100, 3, 100, false) shouldBe true
        }
        expect("a changed fingerprint restarts stability instead of inheriting age") {
            val tracker = WatchReadiness(10)
            tracker.ready(input, 0, 1, 100, false)
            val changed = input.copy(key = "two", size = 11)
            tracker.ready(changed, 200, 2, 100, false) shouldBe false
            tracker.ready(changed, 299, 3, 100, false) shouldBe false
            tracker.ready(changed, 300, 4, 100, false) shouldBe true
        }
        expect("marker readiness ignores age and admitted versions never repeat") {
            val tracker = WatchReadiness(2)
            tracker.ready(input, 0, 1, null, false) shouldBe false
            tracker.ready(input, 1, 2, null, true) shouldBe true
            tracker.admit(input)
            tracker.ready(input, 1000, 3, null, true) shouldBe false
            tracker.ready(input.copy(key = "new", modifiedMillis = 30), 1000, 3, null, true) shouldBe true
        }
        expect("both pending observations and admitted versions consume bounded capacity") {
            val tracker = WatchReadiness(1)
            tracker.ready(input, 0, 1, 100, false)
            shouldThrow<WatchCapacityExceeded> { tracker.ready(input.copy(path = "/second", key = "two"), 0, 1, 100, false) }
            tracker.admit(input)
            tracker.finishScan(2)
            shouldThrow<WatchCapacityExceeded> { tracker.ready(input.copy(path = "/second", key = "two"), 0, 2, null, true) }
        }
        expect("disappeared unready files and changes rejected before processing can restabilize") {
            val tracker = WatchReadiness(1)
            tracker.ready(input, 0, 1, 100, false)
            tracker.finishScan(2)
            tracker.ready(input, 200, 3, null, true) shouldBe true
            tracker.admit(input)
            tracker.changedBeforeProcessing(input)
            tracker.ready(input, 300, 4, 100, false) shouldBe false
        }
        expect("unsafe suffixes, missing policy and invalid limits are rejected") {
            val directories = listOf(Path.of("recordings"))
            shouldThrow<IllegalArgumentException> { WatchOptions(directories, "") }
            shouldThrow<IllegalArgumentException> { WatchOptions(directories, "marker", markerSuffix = "../escape") }
            shouldThrow<IllegalArgumentException> { WatchOptions(directories, "stable", stableSeconds = 0) }
            shouldThrow<IllegalArgumentException> { WatchOptions(directories, "stable", pollSeconds = -1) }
            shouldThrow<IllegalArgumentException> { WatchOptions(directories, "marker", extensions = setOf("*")) }
            shouldThrow<IllegalArgumentException> { WatchOptions(directories, "marker", capacity = 100001) }
        }
    })
