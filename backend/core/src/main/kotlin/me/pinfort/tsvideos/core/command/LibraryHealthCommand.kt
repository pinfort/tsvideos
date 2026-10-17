package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.HealthFinding
import me.pinfort.tsvideos.core.domain.LibraryHealth
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.VerificationStatus
import me.pinfort.tsvideos.core.external.database.mapper.LibraryHealthMapper
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class HealthScanAlreadyRunning : RuntimeException("Verification is already running")

@Component
class LibraryHealthCommand(
    private val mapper: LibraryHealthMapper,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(): LibraryHealth {
        val programs = mapper.programs()
        val names = programs.associate { it.id to it.name }
        val completed = mapper.completed()?.toDomain()
        val findings =
            completed?.let { mapper.findings(it.id) }.orEmpty().map {
                HealthFinding(it.toDomain(), names[it.programId], it.programId !in names)
            }
        return LibraryHealth(
            programs.filter { it.status == Program.Status.ERROR },
            programs.filter { (it.drops ?: -1) > 0 },
            mapper.latest()?.toDomain(),
            completed,
            findings,
            completed?.let { VerificationStatus.entries.associateWith { status -> findings.count { it.finding.status == status } } },
        )
    }
}

@Component
class LibraryHealthScanCommand(
    private val store: HealthVerificationStore,
    private val verifier: LibraryVerificationCommand,
) {
    /**
     * Runs one explicit verification. A failed scan is recorded as a FAILED run (shown to clients through
     * `latestRun`) and logged here, so callers only see [HealthScanAlreadyRunning]; [Error]s are rethrown
     * after the run is released.
     */
    fun scan() {
        val id =
            try {
                store.start()
            } catch (_: DuplicateKeyException) {
                throw HealthScanAlreadyRunning()
            }
        try {
            store.complete(id, checkNotNull(verifier.verify()) { "Verification returned no report" })
        } catch (e: Throwable) {
            logger.error("Library health verification run {} failed", id, e)
            releaseAsFailed(id, e)
            if (e is InterruptedException) Thread.currentThread().interrupt()
            if (e !is Exception) throw e
        }
    }

    /** A run left RUNNING blocks every later scan with 409, so retry once before requiring manual recovery. */
    private fun releaseAsFailed(
        id: Long,
        cause: Throwable,
    ) {
        repeat(FAIL_ATTEMPTS) { attempt ->
            try {
                store.fail(id)
                return
            } catch (e: Exception) {
                cause.addSuppressed(e)
                logger.warn("Could not mark library health run {} as FAILED (attempt {})", id, attempt + 1, e)
            }
        }
        logger.error(
            "Library health run {} is still RUNNING and blocks new scans; see backend/docs/library-health.md for recovery",
            id,
        )
    }

    private companion object {
        const val FAIL_ATTEMPTS = 2
        val logger = LoggerFactory.getLogger(LibraryHealthScanCommand::class.java)
    }
}
