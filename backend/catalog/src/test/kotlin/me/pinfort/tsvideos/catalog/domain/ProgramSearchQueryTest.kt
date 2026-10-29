package me.pinfort.tsvideos.catalog.domain

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class ProgramSearchQueryTest :
    ExpectSpec({
        val at = LocalDateTime.parse("2026-10-12T00:00:00")

        context("shared validation rejects invalid ranges and pagination") {
            withData(
                mapOf<String, () -> ProgramSearchQuery>(
                    "limit = 0" to { ProgramSearchQuery(limit = 0) },
                    "limit = 501" to { ProgramSearchQuery(limit = 501) },
                    "offset = -1" to { ProgramSearchQuery(offset = -1) },
                    "minDrops = -1" to { ProgramSearchQuery(minDrops = -1) },
                    "maxDrops = -1" to { ProgramSearchQuery(maxDrops = -1) },
                    "minDrops > maxDrops" to { ProgramSearchQuery(minDrops = 3, maxDrops = 2) },
                    "recordedFrom == recordedTo" to { ProgramSearchQuery(recordedFrom = at, recordedTo = at) },
                    "recordedFrom after recordedTo" to { ProgramSearchQuery(recordedFrom = at, recordedTo = at.minusDays(1)) },
                ),
            ) { invalid -> shouldThrow<IllegalArgumentException> { invalid() } }
        }
        expect("shared defaults and edge limits are valid") {
            assertSoftly(ProgramSearchQuery()) {
                limit shouldBe 100
                sort shouldBe ProgramSearchQuery.Sort.RECORDED_AT
                direction shouldBe ProgramSearchQuery.Direction.DESC
            }
            ProgramSearchQuery(limit = 1, offset = Int.MAX_VALUE).offset shouldBe Int.MAX_VALUE
            ProgramSearchQuery(limit = 500, minDrops = 0, maxDrops = 0).limit shouldBe 500
        }
    })
