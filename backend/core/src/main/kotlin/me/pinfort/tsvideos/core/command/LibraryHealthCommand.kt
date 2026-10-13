package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.HealthFinding
import me.pinfort.tsvideos.core.domain.LibraryHealth
import me.pinfort.tsvideos.core.domain.VerificationStatus
import me.pinfort.tsvideos.core.external.database.mapper.LibraryHealthMapper
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
            programs.filter { it.status == "ERROR" },
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
    fun scan() {
        val id =
            try {
                store.start()
            } catch (_: DuplicateKeyException) {
                throw HealthScanAlreadyRunning()
            }
        try {
            store.complete(id, checkNotNull(verifier.verify()))
        } catch (_: Exception) {
            store.fail(id)
        }
    }
}
