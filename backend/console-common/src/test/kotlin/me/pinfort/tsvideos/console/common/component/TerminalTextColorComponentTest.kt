package me.pinfort.tsvideos.console.common.component

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

class TerminalTextColorComponentTest :
    ExpectSpec({
        val terminalTextColorComponent = TerminalTextColorComponent()

        context("warn") {
            expect("success") {
                val actual = terminalTextColorComponent.warn("test")

                val expected = "\u001B[${TerminalTextColorComponent.COLOR.YELLOW.code + 10}mtest\u001B[0m"
                actual shouldBe expected
            }
        }

        context("debug") {
            expect("success") {
                val actual = terminalTextColorComponent.debug("test")

                val expected = "\u001B[${TerminalTextColorComponent.COLOR.WHITE.code + 10}mtest\u001B[0m"
                actual shouldBe expected
            }
        }

        context("info") {
            expect("success") {
                val actual = terminalTextColorComponent.info("test")

                val expected = "\u001B[${TerminalTextColorComponent.COLOR.GREEN.code + 10}mtest\u001B[0m"
                actual shouldBe expected
            }
        }

        context("error") {
            expect("success") {
                val actual = terminalTextColorComponent.error("test")

                val expected = "\u001B[${TerminalTextColorComponent.COLOR.RED.code + 10}mtest\u001B[0m"
                actual shouldBe expected
            }
        }
    })
