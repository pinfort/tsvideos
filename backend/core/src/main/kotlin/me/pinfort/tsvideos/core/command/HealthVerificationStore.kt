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
                        it.reason,
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

    private companion object {
        const val INSERT_CHUNK = 500
        val logger = LoggerFactory.getLogger(HealthVerificationStore::class.java)
    }
}
