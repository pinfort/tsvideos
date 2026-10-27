package me.pinfort.tsvideos.catalog.api

import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.persistence.BulkLibraryMapper
import me.pinfort.tsvideos.catalog.persistence.ProgramMapper
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
 * 1. [lockCreatedFilePaths]
 * 2. [lockCreatedFileRows]; or, when the caller re-checks library-wide state under the locks (guarded cleanup),
 *    [lockCreatedFiles] and then [lockWholeLedger]
 * 3. [lockProgramGraph]
 *
 * The bulk locks wait at most 10 seconds (`innodb_lock_wait_timeout`) and then throw a
 * `PessimisticLockingFailureException`. [lockProgramRow] uses the server's default wait.
 */
@Component
class LedgerLocks internal constructor(
    private val locks: BulkLibraryMapper,
    private val programs: ProgramMapper,
) {
    /**
     * Claims the guards (`created_file_path_guard`, migration 076) of every normalized path ([CreatedFilePathKey]) in
     * [paths] and of every split in [splittedFileIds], together in ascending key order. Every catalog write of a
     * `created_file` path claims the guard of the path it writes first ([CreatedFileCatalog.register],
     * [CreatedFileCommand.recordMove]), and a registration also the guard of its split, in the same order. So until this
     * transaction ends no row can be registered at, or moved to, one of these paths or an alias of one, nor registered on
     * one of these splits; such a writer waits before it writes anything. Each guard is a primary-key record lock, so
     * writes of other paths and splits never wait. Call it before the transaction's first plain read: a
     * `REPEATABLE_READ` snapshot taken earlier could miss a row committed before the guard was granted.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockCreatedFilePaths(
        paths: Collection<String>,
        splittedFileIds: Collection<Long>,
    ) {
        CreatedFilePathKey
            .claimOrder(paths.map(CreatedFilePathKey::hash) + splittedFileIds.map(CreatedFilePathKey::splitGuard))
            .forEach { locks.lockPathGuard(it) }
    }

    /** Locks the `created_file` rows [ids] by primary key (record locks only) and returns the ids that exist, ascending. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockCreatedFileRows(ids: Collection<Long>): List<Long> =
        if (ids.isEmpty()) emptyList() else locks.lockFileRows(ids.distinct().sorted())

    /**
     * Locks every `created_file` row (a full scan, so its next-key locks also stop any `created_file` insert) and returns
     * them as read under the lock. Guarded cleanup only: it holds up the pipeline's registrations while held.
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
     * references the same `executed_file`, and returns the program as read after the last lock. Null (with only the
     * program lookup locked) when the program is gone.
     *
     * Only locking reads run before the last lock, so the `REPEATABLE_READ` snapshot taken by the program read (when it
     * is the transaction's first plain read) sees every change committed before the splits were locked, including a
     * registration that held a share lock on one of them ([CreatedFileCatalog.register]).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockProgramGraph(programId: Long): Program? {
        val executedFileId = locks.lockProgramRecording(programId) ?: return null
        locks.lockExecuted(executedFileId)
        locks.lockSplits(executedFileId)
        locks.lockReferences(executedFileId)
        return programs.find(programId)?.toDomain()
    }

    /**
     * Locks the single `program` row [programId] for a change that only follows the program (manual label
     * associations); false when it does not exist. Callers lock the program before their own rows.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockProgramRow(programId: Long): Boolean = locks.lockProgramRow(programId) != null
}
