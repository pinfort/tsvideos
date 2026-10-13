package me.pinfort.tsvideos.processor.console.display

import me.pinfort.tsvideos.processor.infrastructure.pipeline.PathProcessingRunner

/** Each complete line is attributed to an input; no shared carriage-return progress bar. */
class RecordingProgressPrinter(
    private val output: (String) -> Unit = { println(it) },
) {
    private val percentages = mutableMapOf<Pair<String, String>, Int>()

    @Synchronized
    fun progress(event: PathProcessingRunner.Progress) {
        if (event.total <= 0) return
        val percent = (event.value.toDouble() / event.total * 100).toInt().coerceIn(0, 100)
        val key = event.path to event.stage
        if (percentages[key] == percent) return
        percentages[key] = percent
        output("[${event.path}] ${event.stage} $percent%")
    }

    @Synchronized
    fun complete(event: PathProcessingRunner.Completion) {
        percentages.keys.removeAll { it.first == event.path }
        output("[${event.path}] ${event.outcome}")
    }
}
