package me.pinfort.tsvideos.core.catalog

import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.external.database.mapper.BulkLibraryMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Row locks on the ledger (`created_file` / `program` / `executed_file` / `splitted_file`) for callers that change
 * ledger rows under their own transaction. Every lock is `MANDATORY`: a lock outside a transaction would be released
 * at once, so it fails with `IllegalTransactionStateException` instead. Locks are held until the caller's
 * transaction ends; nothing here commits.
 *
 * Coordinated changes call these in exactly this order (backend/docs/module-boundaries.md §7.3):
 * 1. [lockCreatedFiles]
 * 2. [lockWholeLedger], only when the caller re-checks library-wide state under the locks (guarded cleanup)
 * 3. [lockProgramGraph]
 *
 * The bulk locks wait at most 10 seconds (`innodb_lock_wait_timeout`) and then throw a
 * `PessimisticLockingFailureException`. [lockProgramRow] uses the server's default wait.
 */
@Component
class LedgerLocks(
    private val locks: BulkLibraryMapper,
    private val programs: ProgramMapper,
) {
    /**
     * Locks every `created_file` row (a full scan, so its next-key locks also stop new normalized path aliases from
     * being inserted) and returns them as read under the lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockCreatedFiles(): List<CreatedFileSnapshot> = locks.lockFiles().map { it.toSnapshot() }

    /** Locks every `program`, then every `executed_file`, then every `splitted_file` row. Operator CLI only. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockWholeLedger() {
        locks.lockAllPrograms()
        locks.lockAllExecuted()
        locks.lockAllSplits()
    }

    /**
     * Locks the program [programId], then its `executed_file` row, its `splitted_file` rows and every program that
     * references the same `executed_file`. Null (with only the program lookup locked) when the program is gone.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockProgramGraph(programId: Long): Program? {
        val locked = locks.lockProgram(programId)
        val program = locked?.let { programs.find(it)?.toDomain() } ?: return null
        locks.lockExecuted(program.executedFileId)
        locks.lockSplits(program.executedFileId)
        locks.lockReferences(program.executedFileId)
        return program
    }

    /**
     * Locks the single `program` row [programId] for a change that only follows the program (manual label
     * associations); false when it does not exist. Callers lock the program before their own rows.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockProgramRow(programId: Long): Boolean = locks.lockProgramRow(programId) != null
}
