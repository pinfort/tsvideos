package me.pinfort.tsvideos.core.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

class EncodingProfileTest :
    ExpectSpec({
        val input = EncodingProfileInput("BS1", "日本語局", "番組 #1-#12", 3600.0)

        fun policy(
            rules: List<EncodingProfileRule>,
            fallback: String? = "fallback-profile",
        ) = EncodingProfilePolicy(rules, fallback, "legacy", "divided")

        expect("first match wins with AND predicates") {
            val rules =
                listOf(
                    EncodingProfileRule("wrong", "bad", channel = "BS2", titlePattern = "番組"),
                    EncodingProfileRule(
                        "specific",
                        "日本語 profile",
                        channel = "BS1",
                        channelName = "日本語局",
                        titlePattern = "#[0-9]+",
                        minDurationSeconds = 3600.0,
                        maxDurationSeconds = 3600.0,
                    ),
                    EncodingProfileRule("broad", "later", channel = "BS1"),
                )
            policy(rules).select(input) shouldBe EncodingProfileSelection("日本語 profile", "specific", "MATCHED_RULE")
            policy(rules.reversed()).select(input).ruleId shouldBe "broad"
            policy(rules).select(input.copy(channel = "OTHER")).reason shouldBe "FALLBACK"
        }
        expect("duration bounds inclusive and unknown duration never matches") {
            val rule = EncodingProfileRule("duration", "profile", minDurationSeconds = 10.0, maxDurationSeconds = 20.0)
            for (value in listOf(10.0, 15.0, 20.0)) rule.matches(input.copy(durationSeconds = value)) shouldBe true
            for (value in listOf(9.0, 21.0, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                rule.matches(input.copy(durationSeconds = value)) shouldBe false
            }
            EncodingProfileRule("channel", "profile", channel = "BS1").matches(input.copy(durationSeconds = 0.0)) shouldBe true
        }
        expect("no rules preserves legacy selection") {
            val legacy = policy(emptyList(), null)
            legacy.select(input).profile shouldBe "legacy"
            val atx = input.copy(channelName = "ＡＴ－Ｘ", durationSeconds = 10801.0)
            legacy.select(atx).profile shouldBe "divided"
            legacy.select(atx.copy(durationSeconds = 10800.0)).profile shouldBe "legacy"
            legacy.select(atx.copy(title = "通常番組")).profile shouldBe "legacy"
            legacy.select(atx.copy(channelName = "AT-X")).profile shouldBe "legacy"
            policy(emptyList(), "unused").select(input).profile shouldBe "legacy"
        }
        expect("explicit fallback and unique IDs required") {
            val rule = EncodingProfileRule("one", "profile", channel = "BS1")
            shouldThrow<IllegalArgumentException> { policy(listOf(rule), null) }
            shouldThrow<IllegalArgumentException> { policy(listOf(rule, rule)) }
            shouldThrow<IllegalArgumentException> { policy(listOf(rule), " ") }
        }
        expect("invalid rules fail before selection") {
            shouldThrow<IllegalArgumentException> { EncodingProfileRule("bad id", "profile", channel = "BS1") }
            shouldThrow<IllegalArgumentException> { EncodingProfileRule("one", "profile") }
            shouldThrow<IllegalArgumentException> { EncodingProfileRule("one", "profile", titlePattern = "[") }
            shouldThrow<IllegalArgumentException> { EncodingProfileRule("one", "profile", titlePattern = "") }
            shouldThrow<IllegalArgumentException> { EncodingProfileRule("one", "profile", channel = " ") }
            shouldThrow<IllegalArgumentException> { EncodingProfileRule("one", "bad\nprofile", channel = "BS1") }
            shouldThrow<IllegalArgumentException> {
                EncodingProfileRule(
                    "one",
                    "profile",
                    minDurationSeconds = 20.0,
                    maxDurationSeconds = 10.0,
                )
            }
            for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                shouldThrow<IllegalArgumentException> { EncodingProfileRule("one", "profile", minDurationSeconds = value) }
            }
        }
        expect("policy snapshots configured order") {
            val rules = mutableListOf(EncodingProfileRule("one", "profile", channel = "BS1"))
            val configured = policy(rules)
            rules.clear()
            configured.select(input).profile shouldBe "profile"
        }
    })
