package me.pinfort.tsvideos.core.command

import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.domain.ProcessingJob
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingJobMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** Separate transactions release a duplicate INSERT's index locks before attempting the retry CAS. */
@Component
class ProcessingJobClaimCommand(
    private val mapper: ProcessingJobMapper,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun insert(identity: ProcessingIdentity): ProcessingJob {
        check(mapper.claim(identity.key, identity.path, identity.size, identity.modifiedMillis) == 1)
        return checkNotNull(mapper.find(identity.key))
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun retry(identity: ProcessingIdentity): ProcessingJob? {
        if (mapper.retry(identity.key) != 1) return null
        return checkNotNull(mapper.find(identity.key))
    }
}
