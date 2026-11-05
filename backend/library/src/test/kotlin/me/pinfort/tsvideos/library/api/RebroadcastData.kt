package me.pinfort.tsvideos.library.api

import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.catalog.api.ProgramInventory
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Test helpers shared by the #208 fixture regression test and the optional external-data validation. Programs are read
 * from the `tvmcli search --json` shape (`{"programs": [...]}` or a bare array); fields that the detector does not use
 * get harmless defaults, so a fixture needs only id/title/channel/recordedAt/duration.
 */
internal object RebroadcastData {
    val mapper: JsonMapper = JsonMapper.builder().build()

    fun programs(root: JsonNode): List<Program> {
        val rows = if (root.isArray) root else root["programs"] ?: error("no \"programs\" array")
        return rows.items().map(::program)
    }

    /**
     * The elements of an array node as a Kotlin list. Jackson 3's JsonNode has a member `map` (Optional-style mapping of
     * the node itself) that shadows Kotlin's Iterable.map, so iterate through the Iterable type explicitly.
     */
    fun JsonNode.items(): List<JsonNode> {
        check(isArray) { "expected a JSON array, found $nodeType" }
        return (this as Iterable<JsonNode>).toList()
    }

    fun program(row: JsonNode): Program {
        fun text(name: String): String? = row[name]?.takeUnless { it.isNull }?.asString()
        val id = row["id"].asLong()
        return Program(
            id = id,
            name = text("name") ?: "program$id",
            executedFileId = row["executedFileId"]?.asLong() ?: id,
            status = text("status")?.let { Program.Status.valueOf(it) } ?: Program.Status.COMPLETED,
            drops = row["drops"]?.asInt() ?: 0,
            size = row["size"]?.asLong() ?: 0,
            recordedAt = text("recordedAt")?.let(::dateTime) ?: LocalDateTime.MIN,
            channel = text("channel").orEmpty(),
            title = text("title").orEmpty(),
            channelName = text("channelName").orEmpty(),
            duration = row["duration"]?.asDouble() ?: -1.0,
        )
    }

    private fun dateTime(value: String): LocalDateTime =
        try {
            LocalDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            LocalDateTime.MIN
        }

    /** Runs the production detector (DuplicateProgramCommand.detect) over [programs]. */
    fun detect(programs: List<Program>): List<DuplicateGroup> {
        val inventory = mockk<ProgramInventory>()
        every { inventory.recordedPrograms() } returns programs
        return DuplicateProgramCommand(inventory).detect()
    }

    fun idSets(
        groups: List<DuplicateGroup>,
        reason: DuplicateGroup.Reason,
    ): Set<Set<Long>> = groups.filter { it.reason == reason }.map { group -> group.programs.map { it.id }.toSet() }.toSet()

    /** Every program id that appears in more than one group (must be empty). */
    fun sharedMembers(groups: List<DuplicateGroup>): List<Long> =
        groups
            .flatMap { group -> group.programs.map { it.id } }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()

    /**
     * A frozen copy of the pre-#208 detector (SAME_BROADCAST/OVERLAP only, commit 24360b3), used as the baseline the
     * new detector must reproduce exactly. Do not "fix" this copy: it exists to catch changes to the first stage.
     */
    fun baseline(
        programs: List<Program>,
        minOverlapRatio: Double = DuplicateProgramCommand.DEFAULT_MIN_OVERLAP_RATIO,
    ): List<DuplicateGroup> {
        val keepOrder = DuplicateProgramCommand.keepCriteria.map { it.second }.reduce { a, b -> a.then(b) }
        val targets = programs.filter { it.recordedAt != LocalDateTime.MIN && it.channel.isNotEmpty() }
        val parents = IntArray(targets.size) { it }

        fun find(i: Int): Int {
            var root = i
            while (parents[root] != root) root = parents[root]
            parents[i] = root
            return root
        }

        fun start(program: Program) = program.recordedAt.toEpochSecond(ZoneOffset.UTC).toDouble()

        fun end(program: Program) = start(program) + program.duration

        fun overlapRatio(
            a: Program,
            b: Program,
        ) = (minOf(end(a), end(b)) - start(b)) / maxOf(a.duration, b.duration)

        targets.indices
            .groupBy { targets[it].channel }
            .values
            .forEach { indices ->
                val sorted = indices.sortedBy { targets[it].recordedAt }
                for (i in sorted.indices) {
                    val a = targets[sorted[i]]
                    for (j in i + 1 until sorted.size) {
                        val b = targets[sorted[j]]
                        if (b.recordedAt == a.recordedAt) {
                            if (a.duration <= 0 || b.duration <= 0 || overlapRatio(a, b) >= minOverlapRatio) {
                                parents[find(sorted[i])] = find(sorted[j])
                            }
                            continue
                        }
                        if (a.duration <= 0 || start(b) >= end(a)) break
                        if (b.duration <= 0) continue
                        if (overlapRatio(a, b) >= minOverlapRatio) parents[find(sorted[i])] = find(sorted[j])
                    }
                }
            }
        return targets.indices
            .groupBy { find(it) }
            .values
            .filter { it.size > 1 }
            .map { indices ->
                val members = indices.map { targets[it] }
                val reason =
                    if (members.all { it.recordedAt == members.first().recordedAt }) {
                        DuplicateGroup.Reason.SAME_BROADCAST
                    } else {
                        DuplicateGroup.Reason.OVERLAP
                    }
                DuplicateGroup(reason, members.sortedWith(keepOrder))
            }.sortedBy { group -> group.programs.minOf { it.recordedAt } }
    }

    fun readTree(file: File): JsonNode = file.inputStream().use { mapper.readTree(it) }
}
