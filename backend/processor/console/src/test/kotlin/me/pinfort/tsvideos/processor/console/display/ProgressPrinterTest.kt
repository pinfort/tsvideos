package me.pinfort.tsvideos.processor.console.display

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ProgressPrinterTest :
    ExpectSpec({
        val originalOut = System.out
        lateinit var captured: ByteArrayOutputStream

        fun bar(percent: Int): String {
            val filled = 30 * percent / 100
            return "#".repeat(filled) + "-".repeat(30 - filled)
        }

        beforeTest {
            captured = ByteArrayOutputStream()
            System.setOut(PrintStream(captured, true))
        }

        afterTest {
            System.setOut(originalOut)
        }

        context("render") {
            expect("starts the first frame of a transfer on its own line, without a leading carriage return") {
                val printer = ProgressPrinter("Compressing")

                printer.render(500, 1000)

                captured.toString() shouldBe "Compressing [${bar(50)}] 50%"
            }

            expect("emits nothing when the total size is not positive") {
                val printer = ProgressPrinter("Compressing")

                printer.render(10, 0)
                printer.render(10, -1)

                captured.toString() shouldBe ""
            }

            expect("does not re-render while the rendered percent is unchanged") {
                val printer = ProgressPrinter("Compressing")
                printer.render(500, 1000)
                captured.reset()

                printer.render(504, 1000)

                captured.toString() shouldBe ""
            }

            expect("re-renders once the percent advances") {
                val printer = ProgressPrinter("Uploading")
                printer.render(500, 1000)
                captured.reset()

                printer.render(750, 1000)

                captured.toString() shouldBe "\rUploading [${bar(75)}] 75%"
            }

            expect("prints a trailing newline when the transfer reaches 100%") {
                val printer = ProgressPrinter("Compressing")

                printer.render(1000, 1000)

                captured.toString() shouldBe "Compressing [${bar(100)}] 100%" + System.lineSeparator()
            }

            expect("overwrites the open bar line once the transfer reaches 100%") {
                val printer = ProgressPrinter("Compressing")
                printer.render(500, 1000)
                captured.reset()

                printer.render(1000, 1000)

                captured.toString() shouldBe "\rCompressing [${bar(100)}] 100%" + System.lineSeparator()
            }

            expect("starts the next transfer on a new line instead of overwriting the previous line") {
                val printer = ProgressPrinter("Uploading")
                printer.render(1000, 1000)
                captured.reset()

                printer.render(0, 1000)

                captured.toString() shouldBe "Uploading [${bar(0)}] 0%"
            }

            expect("ends an unfinished bar before re-rendering for a new total size even if the percent matches") {
                val printer = ProgressPrinter("Compressing")
                printer.render(500, 1000)
                captured.reset()

                printer.render(1, 2)

                captured.toString() shouldBe System.lineSeparator() + "Compressing [${bar(50)}] 50%"
            }

            expect("clamps the percent to 100 when bytesTransferred exceeds totalBytes") {
                val printer = ProgressPrinter("Compressing")

                printer.render(1500, 1000)

                captured.toString() shouldBe "Compressing [${bar(100)}] 100%" + System.lineSeparator()
            }
        }
    })
