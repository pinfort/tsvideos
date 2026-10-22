package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.catalog.api.ProgramInventory
import me.pinfort.tsvideos.catalog.api.ProgramSummary
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.core.domain.HealthFinding
import me.pinfort.tsvideos.core.domain.HealthProgram
import me.pinfort.tsvideos.core.domain.LibraryHealth
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
    private val programs: ProgramInventory,
) {
    // The catalog reads join this transaction, so findings and program names come from one snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(): LibraryHealth {
        val completed = mapper.completed()?.toDomain()
        val findings =
            completed
                ?.let { run ->
                    val rows = mapper.findings(run.id)
                    // A finding whose program is absent from the catalog refers to a deleted program.
                    val names = programs.summaries(rows.map { it.programId }).associate { it.id to it.name }
                    rows.map { HealthFinding(it.toDomain(), names[it.programId], it.programId !in names) }
                }.orEmpty()
        return LibraryHealth(
            programs.withStatus(Program.Status.ERROR).map { it.toHealthProgram() },
            programs.withDrops().map { it.toHealthProgram() },
            mapper.latest()?.toDomain(),
            completed,
            findings,
            completed?.let { VerificationStatus.entries.associateWith { status -> findings.count { it.finding.status == status } } },
        )
    }

    private fun ProgramSummary.toHealthProgram() = HealthProgram(id, name, status, drops)
}

@Component
class LibraryHealthScanCommand(
    private val store: HealthVerificationStore,
    private val verifier: LibraryVerificationCommand,
) {
    /**
     * Runs one explicit verification. A failed scan is recorded as a FAILED run (shown to clients through
     * `latestRun`) and logged here, so callers only see [HealthScanAlreadyRunning]; an [Error] is rethrown
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
        } catch (e: Exception) {
            logger.error("Library health verification run {} failed", id, e)
            releaseAsFailed(id, e)
            if (e is InterruptedException) Thread.currentThread().interrupt()
        } catch (e: Error) {
            logger.error("Library health verification run {} failed", id, e)
            releaseAsFailed(id, e)
            throw e
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
