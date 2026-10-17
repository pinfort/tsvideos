package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.HealthRun
import me.pinfort.tsvideos.core.domain.VerificationReport
import me.pinfort.tsvideos.core.external.database.dto.HealthFindingDto
import me.pinfort.tsvideos.core.external.database.mapper.GeneratedKeyHolder
import me.pinfort.tsvideos.core.external.database.mapper.LibraryHealthMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Short database transactions: NAS I/O is deliberately outside this component. */
@Component
class HealthVerificationStore(
    private val mapper: LibraryHealthMapper,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun start(): Long {
        val key = GeneratedKeyHolder()
        mapper.start(LocalDateTime.now(ZoneOffset.UTC), key)
        return key.id
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(
        runId: Long,
        report: VerificationReport,
    ) {
        // Multi-row inserts, chunked so a large library stays within statement/packet limits.
        report.findings.chunked(INSERT_CHUNK).forEach { chunk ->
            val rows =
                chunk.map {
                    HealthFindingDto(
                        it.programId,
                        it.fileId,
                        it.file,
                        it.status,
                        LocalDateTime.ofInstant(it.checkedAt, ZoneOffset.UTC),
                        it.expectedBytes,
                        it.actualBytes,
                        it.reason?.let(::truncateReason),
                    )
                }
            check(mapper.insertFindings(runId, rows) == rows.size)
        }
        check(mapper.finish(runId, HealthRun.State.COMPLETED, LocalDateTime.now(ZoneOffset.UTC), null) == 1)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(runId: Long) {
        if (mapper.finish(runId, HealthRun.State.FAILED, LocalDateTime.now(ZoneOffset.UTC), "VERIFICATION_FAILED") == 0) {
            logger.warn("Library health run {} was no longer RUNNING when marking it FAILED", runId)
        }
    }

    /**
     * Marks every RUNNING run FAILED with [INTERRUPTED]. Only safe when no other process can own a live scan, i.e.
     * at startup of the single manager:api instance that creates runs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failAbandoned(): Int = mapper.failAllRunning(LocalDateTime.now(ZoneOffset.UTC), INTERRUPTED)

    companion object {
        const val INTERRUPTED = "INTERRUPTED"

        /** `health_verification_finding.reason` is VARCHAR(255); a longer verifier message must not fail the run. */
        const val REASON_MAX_CHARS = 255

        private const val INSERT_CHUNK = 500
        private val logger = LoggerFactory.getLogger(HealthVerificationStore::class.java)

        /** Truncates by code point so a surrogate pair is never split. */
        internal fun truncateReason(reason: String): String =
            if (reason.codePointCount(0, reason.length) <= REASON_MAX_CHARS) {
                reason
            } else {
                reason.substring(0, reason.offsetByCodePoints(0, REASON_MAX_CHARS - 1)) + "…"
            }
    }
}
