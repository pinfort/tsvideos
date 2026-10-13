package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.DuplicateLibraryNameException
import me.pinfort.tsvideos.core.domain.LibraryItemNotFoundException
import me.pinfort.tsvideos.core.domain.LibraryKind
import me.pinfort.tsvideos.core.domain.LibraryLabel
import me.pinfort.tsvideos.core.domain.ProgramOrganization
import me.pinfort.tsvideos.core.external.database.mapper.GeneratedKeyHolder
import me.pinfort.tsvideos.core.external.database.mapper.LibraryOrganizationMapper
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer
import java.util.Locale

@Component
class LibraryOrganizationCommand(
    private val mapper: LibraryOrganizationMapper,
) {
    fun list(kind: LibraryKind): List<LibraryLabel> = mapper.list(kind)

    fun find(
        kind: LibraryKind,
        id: Long,
    ): LibraryLabel {
        positive(id)
        return mapper.find(kind, id) ?: throw LibraryItemNotFoundException()
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun forProgram(programId: Long): ProgramOrganization {
        positive(programId)
        if (mapper.findProgram(programId) == null) throw LibraryItemNotFoundException()
        return ProgramOrganization(mapper.forProgram(LibraryKind.COLLECTION, programId), mapper.forProgram(LibraryKind.TAG, programId))
    }

    @Transactional
    fun create(
        kind: LibraryKind,
        input: String,
    ): LibraryLabel {
        val name = name(input)
        val key = GeneratedKeyHolder()
        try {
            mapper.insert(kind, name, name.lowercase(Locale.ROOT), key)
        } catch (
            _: DuplicateKeyException,
        ) {
            throw DuplicateLibraryNameException()
        }
        return mapper.find(kind, key.id)!!
    }

    @Transactional
    fun rename(
        kind: LibraryKind,
        id: Long,
        input: String,
    ): LibraryLabel {
        positive(id)
        val name = name(input)
        if (mapper.lock(kind, id) == null) throw LibraryItemNotFoundException()
        try {
            mapper.rename(kind, id, name, name.lowercase(Locale.ROOT))
        } catch (
            _: DuplicateKeyException,
        ) {
            throw DuplicateLibraryNameException()
        }
        return mapper.find(kind, id)!!
    }

    @Transactional
    fun delete(
        kind: LibraryKind,
        id: Long,
    ) {
        positive(id)
        if (mapper.delete(kind, id) == 0) throw LibraryItemNotFoundException()
    }

    @Transactional
    fun assign(
        kind: LibraryKind,
        id: Long,
        programId: Long,
        assigned: Boolean,
    ) {
        positive(id)
        positive(programId)
        if (mapper.lock(kind, id) == null || mapper.lockProgram(programId) == null) throw LibraryItemNotFoundException()
        if (assigned) mapper.assign(kind, id, programId) else mapper.unassign(kind, id, programId)
    }

    @Transactional
    fun assignTag(
        programId: Long,
        input: String,
    ): LibraryLabel {
        positive(programId)
        val name = name(input)
        val key = GeneratedKeyHolder()
        // The upsert locks the label first, matching assignment's lock order.
        mapper.reuse(LibraryKind.TAG, name, name.lowercase(Locale.ROOT), key)
        if (mapper.lockProgram(programId) == null) throw LibraryItemNotFoundException()
        mapper.assign(LibraryKind.TAG, key.id, programId)
        return mapper.find(LibraryKind.TAG, key.id)!!
    }

    private fun positive(id: Long) {
        require(id > 0) { "IDs must be positive" }
    }

    private fun name(input: String): String {
        val name = Normalizer.normalize(input.trim(), Normalizer.Form.NFC)
        require(name.isNotBlank() && name.length <= 100 && name.none(Char::isISOControl)) {
            "Name must contain 1 to 100 characters without control characters"
        }
        return name
    }
}
