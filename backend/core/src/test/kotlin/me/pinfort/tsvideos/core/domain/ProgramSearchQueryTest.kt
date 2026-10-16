package me.pinfort.tsvideos.core.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class ProgramSearchQueryTest :
    ExpectSpec({
        expect("shared validation rejects invalid ranges and pagination") {
            val at = LocalDateTime.parse("2026-10-12T00:00:00")
            listOf<() -> ProgramSearchQuery>(
                { ProgramSearchQuery(limit = 0) },
                { ProgramSearchQuery(limit = 501) },
                { ProgramSearchQuery(offset = -1) },
                { ProgramSearchQuery(minDrops = -1) },
                { ProgramSearchQuery(maxDrops = -1) },
                { ProgramSearchQuery(minDrops = 3, maxDrops = 2) },
                { ProgramSearchQuery(recordedFrom = at, recordedTo = at) },
                { ProgramSearchQuery(recordedFrom = at, recordedTo = at.minusDays(1)) },
            ).forEach { invalid -> shouldThrow<IllegalArgumentException> { invalid() } }
        }
        expect("shared defaults and edge limits are valid") {
            ProgramSearchQuery().limit shouldBe 100
            ProgramSearchQuery().sort shouldBe ProgramSearchQuery.Sort.RECORDED_AT
            ProgramSearchQuery().direction shouldBe ProgramSearchQuery.Direction.DESC
            ProgramSearchQuery(limit = 1, offset = Int.MAX_VALUE).offset shouldBe Int.MAX_VALUE
            ProgramSearchQuery(limit = 500, minDrops = 0, maxDrops = 0).limit shouldBe 500
        }
    })
