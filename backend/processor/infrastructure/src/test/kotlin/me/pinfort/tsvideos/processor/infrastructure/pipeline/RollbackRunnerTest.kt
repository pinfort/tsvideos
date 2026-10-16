package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

class RollbackRunnerTest :
    ExpectSpec({
        expect("attempts every rollback in reverse order and preserves the original failure") {
            val runner = RollbackRunner()
            val calls = mutableListOf<Int>()
            val failure = IllegalStateException("stage failed")
            val cleanupFailure = IllegalArgumentException("cleanup failed")
            runner.stage({ calls.add(1) }) { "first" } shouldBe "first"
            runner.stage({
                calls.add(2)
                throw cleanupFailure
                expect("reports a clean rollback when every rollback succeeds") {
                    val runner = RollbackRunner()
                    runner.stage({}) { "first" }
                    shouldThrow<IllegalStateException> { runner.stage({}) { error("stage failed") } }
                    runner.rolledBackCleanly shouldBe true
                }
            }) { "second" }
            val thrown =
                shouldThrow<IllegalStateException> {
                    runner.stage({
                        calls.add(3)
                        throw failure
                        expect("reports a clean rollback when every rollback succeeds") {
                            val runner = RollbackRunner()
                            runner.stage({}) { "first" }
                            shouldThrow<IllegalStateException> { runner.stage({}) { error("stage failed") } }
                            runner.rolledBackCleanly shouldBe true
                        }
                    }) { throw failure }
                }
            thrown shouldBe failure
            thrown.suppressed.toList() shouldBe listOf(cleanupFailure)
            calls shouldBe listOf(3, 2, 1)
            runner.rolledBackCleanly shouldBe false
        }
        expect("does not roll back successful work") {
            val runner = RollbackRunner()
            var rolledBack = false
            runner.stage({ rolledBack = true }) { 42 } shouldBe 42
            rolledBack shouldBe false
            runner.rolledBackCleanly shouldBe true
        }
        expect("reports a clean rollback when every rollback succeeds") {
            val runner = RollbackRunner()
            runner.stage({}) { "first" }
            shouldThrow<IllegalStateException> { runner.stage({}) { error("stage failed") } }
            runner.rolledBackCleanly shouldBe true
        }
    })
