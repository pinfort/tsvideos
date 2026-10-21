package me.pinfort.tsvideos.processor.console.display

class ProgressPrinter(
    private val label: String,
) {
    private var lastRenderedPercent: Int? = null
    private var lastTotalBytes: Long? = null

    // Whether the cursor sits on this printer's unfinished bar line. Only then does a render start with '\r':
    // Amatsukaze's log treats a leading '\r' as replacing the previous line, so the first frame of a transfer must
    // start a line of its own instead of overwriting whatever was printed last.
    private var lineOpen = false

    fun render(
        bytesTransferred: Long,
        totalBytes: Long,
    ) {
        if (totalBytes <= 0) return
        if (totalBytes != lastTotalBytes) {
            lastTotalBytes = totalBytes
            lastRenderedPercent = null
            // A new transfer: finish a bar the previous one left unfinished rather than overwriting it.
            if (lineOpen) println()
            lineOpen = false
        }
        val percent = (bytesTransferred * 100 / totalBytes).toInt().coerceIn(0, 100)
        if (percent == lastRenderedPercent) return
        lastRenderedPercent = percent
        val filled = PROGRESS_BAR_WIDTH * percent / 100
        val bar = "#".repeat(filled) + "-".repeat(PROGRESS_BAR_WIDTH - filled)
        print("${if (lineOpen) "\r" else ""}$label [$bar] $percent%")
        System.out.flush()
        lineOpen = percent < 100
        if (!lineOpen) println()
    }

    private companion object {
        const val PROGRESS_BAR_WIDTH = 30
    }
}
