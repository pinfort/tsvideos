package me.pinfort.tsvideos.core.command

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.command.HealthVerificationStore.Companion.REASON_MAX_CHARS
import me.pinfort.tsvideos.core.command.HealthVerificationStore.Companion.truncateReason

class HealthVerificationStoreTest :
    ExpectSpec({
        fun codePoints(value: String) = value.codePointCount(0, value.length)

        expect("a reason of exactly the column width is kept unchanged") {
            val reason = "x".repeat(REASON_MAX_CHARS)
            truncateReason(reason) shouldBe reason
        }
        expect("one code point over the width is cut and marked") {
            val result = truncateReason("x".repeat(REASON_MAX_CHARS + 1))
            codePoints(result) shouldBe REASON_MAX_CHARS
            result shouldBe "x".repeat(REASON_MAX_CHARS - 1) + "…"
        }
        expect("a surrogate pair at the cut is never split") {
            val reason = "x".repeat(REASON_MAX_CHARS - 2) + "😀😀😀"
            val result = truncateReason(reason)
            result shouldBe "x".repeat(REASON_MAX_CHARS - 2) + "😀…"
        }
        expect("exactly the width in surrogate pairs counts code points, not UTF-16 units") {
            val reason = "😀".repeat(REASON_MAX_CHARS)
            truncateReason(reason) shouldBe reason
        }
    })
