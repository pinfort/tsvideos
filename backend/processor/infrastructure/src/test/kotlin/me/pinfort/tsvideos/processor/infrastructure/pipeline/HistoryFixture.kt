package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.processing.api.ProcessingHistoryCommand
import me.pinfort.tsvideos.processing.domain.ProcessingAttempt
import me.pinfort.tsvideos.processing.domain.ProcessingAttemptKind
import me.pinfort.tsvideos.processing.domain.ProcessingOutcome

/** Keep existing pipeline tests executing their real stages while observing the history boundary. */
internal fun historyFixture(): ProcessingHistoryCommand =
    mockk<ProcessingHistoryCommand>(relaxed = true).also { history ->
        every { history.begin(any(), any()) } returns
            ProcessingAttempt(
                44,
                1,
                1,
                ProcessingAttemptKind.PROCESS,
                ProcessingOutcome.ACTIVE,
                "2026-01-01T00:00:00Z",
                null,
                "2026-01-01T00:00:00Z",
                null,
            )
        every { history.stage<Any?>(any(), any(), any(), any()) } answers { lastArg<() -> Any?>().invoke() }
        every { history.progress(any(), any()) } answers { secondArg() }
    }
