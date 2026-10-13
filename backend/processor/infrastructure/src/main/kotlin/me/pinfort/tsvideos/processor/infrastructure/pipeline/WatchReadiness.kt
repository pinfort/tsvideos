package me.pinfort.tsvideos.processor.infrastructure.pipeline

import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import java.nio.file.Path
import java.time.Duration

/** Pure bounded metadata observation; monotonic time is supplied by the scanner. */
class WatchReadiness(
    private val capacity: Int,
) {
    private data class Observation(
        val identity: ProcessingIdentity,
        val since: Long,
        var count: Int,
        var scan: Long,
    )

    private val observed = mutableMapOf<String, Observation>()
    private val admitted = mutableSetOf<String>()

    @Synchronized
    fun ready(
        identity: ProcessingIdentity,
        now: Long,
        scan: Long,
        stableNanos: Long?,
        markerReady: Boolean,
    ): Boolean {
        if (identity.key in admitted) return false
        val previous = observed[identity.path]
        if (previous == null || previous.identity != identity) {
            if (previous == null && observed.size + admitted.size >= capacity) throw WatchCapacityExceeded()
            observed[identity.path] = Observation(identity, now, 1, scan)
            return stableNanos == null && markerReady
        }
        if (previous.scan != scan) previous.count++
        previous.scan = scan
        return if (stableNanos == null) markerReady else previous.count >= 2 && now - previous.since >= stableNanos
    }

    @Synchronized
    fun admit(identity: ProcessingIdentity) {
        observed.remove(identity.path)
        admitted.add(identity.key)
    }

    @Synchronized
    fun changedBeforeProcessing(identity: ProcessingIdentity) {
        admitted.remove(identity.key)
        observed.remove(identity.path)
    }

    @Synchronized
    fun finishScan(scan: Long) {
        observed.entries.removeIf { it.value.scan != scan }
    }
}

class WatchedInputChanged : IllegalStateException("WATCH_INPUT_CHANGED")

class WatchCapacityExceeded : IllegalStateException("WATCH_CAPACITY_EXCEEDED")

data class WatchOptions(
    val directories: List<Path>,
    val completion: String,
    val markerSuffix: String = ".ready",
    val stableSeconds: Long = 30,
    val pollSeconds: Long = 5,
    val extensions: Set<String> = setOf(".m2ts"),
    val capacity: Int = 10000,
) {
    init {
        require(directories.isNotEmpty() && directories.size <= 32) { "WATCH_INVALID_DIRECTORIES" }
        require(completion in setOf("marker", "stable")) { "WATCH_INVALID_COMPLETION" }
        require(
            markerSuffix.isNotBlank() &&
                markerSuffix.length <= 64 &&
                markerSuffix.none {
                    it == '/' || it == '\\' || it.isISOControl()
                },
        ) { "WATCH_INVALID_MARKER_SUFFIX" }
        require(stableSeconds in 1..86400 && pollSeconds in 1..3600) { "WATCH_INVALID_INTERVAL" }
        require(
            extensions.isNotEmpty() &&
                extensions.size <= 32 &&
                extensions.all {
                    it.matches(Regex("\\.[A-Za-z0-9]{1,16}"))
                },
        ) { "WATCH_INVALID_EXTENSION" }
        require(capacity in 1..100000) { "WATCH_INVALID_CAPACITY" }
    }

    val stableNanos: Long? get() = if (completion == "stable") Duration.ofSeconds(stableSeconds).toNanos() else null
}
