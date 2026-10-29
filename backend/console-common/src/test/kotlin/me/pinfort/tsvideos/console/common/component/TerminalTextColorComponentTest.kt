package me.pinfort.tsvideos.console.common.component

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class TerminalTextColorComponentTest :
    ExpectSpec({
        val terminalTextColorComponent = TerminalTextColorComponent()

        context("decorates the text with the level's background color") {
            withData(
                mapOf<String, Pair<(String) -> String, TerminalTextColorComponent.COLOR>>(
                    "warn" to (terminalTextColorComponent::warn to TerminalTextColorComponent.COLOR.YELLOW),
                    "debug" to (terminalTextColorComponent::debug to TerminalTextColorComponent.COLOR.WHITE),
                    "info" to (terminalTextColorComponent::info to TerminalTextColorComponent.COLOR.GREEN),
                    "error" to (terminalTextColorComponent::error to TerminalTextColorComponent.COLOR.RED),
                ),
            ) { (colorize, color) ->
                colorize("test") shouldBe "\u001B[${color.code + 10}mtest\u001B[0m"
            }
        }
    })
