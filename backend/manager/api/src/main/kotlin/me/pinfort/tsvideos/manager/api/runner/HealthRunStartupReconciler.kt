package me.pinfort.tsvideos.manager.api.runner

import me.pinfort.tsvideos.library.api.HealthVerificationStore
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Library health scans run inside manager:api requests, so a RUNNING run seen at startup was abandoned by a
 * previous process (restart, deploy, crash) and would otherwise block every scan with 409. Disable with
 * `tsvideos.health.reconcile-on-startup=false` when several manager:api instances share one database.
 */
@Component
@ConditionalOnProperty("tsvideos.health.reconcile-on-startup", havingValue = "true", matchIfMissing = true)
class HealthRunStartupReconciler(
    private val store: HealthVerificationStore,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        // Best effort: an unreachable database must not stop the API from starting; recovery stays manual then.
        val released =
            try {
                store.failAbandoned()
            } catch (e: Exception) {
                logger.warn("Could not release abandoned library health runs at startup", e)
                return
            }
        if (released > 0) logger.warn("Marked {} abandoned library health run(s) as FAILED (INTERRUPTED)", released)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(HealthRunStartupReconciler::class.java)
    }
}
