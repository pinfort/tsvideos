package me.pinfort.tsvideos.processor.console.display

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.processor.infrastructure.pipeline.PathProcessingRunner

class RecordingProgressPrinterTest :
    ExpectSpec({
        expect("concurrent callbacks produce attributed whole lines and independent percentages") {
            val lines = mutableListOf<String>()
            val printer = RecordingProgressPrinter { lines.add(it) }
            val workers =
                (1..4).map { id ->
                    Thread {
                        repeat(20) { printer.progress(PathProcessingRunner.Progress("recording-$id", "UPLOAD", 1, 2)) }
                        printer.complete(PathProcessingRunner.Completion("recording-$id", "PROCESSED"))
                    }.apply { start() }
                }
            workers.forEach { it.join(5000) }
            lines.size shouldBe 8
            (1..4).forEach { id ->
                lines.count { it == "[recording-$id] UPLOAD 50%" } shouldBe 1
                lines.count { it == "[recording-$id] PROCESSED" } shouldBe 1
            }
        }
    })
