package me.pinfort.tsvideos.manager.console.component

import me.pinfort.tsvideos.library.domain.CleanupCriteria
import me.pinfort.tsvideos.library.domain.CleanupFile
import me.pinfort.tsvideos.library.domain.CleanupGroup
import me.pinfort.tsvideos.library.domain.CleanupMode
import me.pinfort.tsvideos.library.domain.CleanupPlan
import me.pinfort.tsvideos.library.domain.CleanupTarget
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Strict versioned import. Paths are review metadata and are never passed to file deletion. */
object CleanupPlanJson {
    private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()

    fun write(value: Any): String = mapper.writeValueAsString(value)

    fun read(value: String): CleanupPlan {
        val root = mapper.readTree(value)
        keys(root, "schemaVersion", "createdAt", "action", "criteria", "groups", "targets", "keptTargets")
        val criteria = root["criteria"]
        keys(criteria, "mode", "recordedBefore", "channel", "keepIds")
        return CleanupPlan(
            integer(root["schemaVersion"]).also { require(it == 1L) }.toInt(),
            text(root["createdAt"]),
            text(root["action"]),
            CleanupCriteria(
                CleanupMode.valueOf(text(criteria["mode"])),
                optional(criteria["recordedBefore"]),
                optional(criteria["channel"]),
                array(criteria["keepIds"]).map(::integer),
            ),
            array(root["groups"]).map {
                keys(it, "memberIds", "keepId")
                CleanupGroup(array(it["memberIds"]).map(::integer), integer(it["keepId"]))
            },
            array(root["targets"]).map(::target),
            array(root["keptTargets"]).map(::target),
        )
    }

    private fun target(node: JsonNode): CleanupTarget {
        keys(node, "id", "fingerprint", "reason", "eligible", "estimatedMetadataBytes", "originalFile", "splitFiles", "createdFiles")
        require(node["eligible"].isBoolean)
        return CleanupTarget(
            integer(node["id"]),
            text(node["fingerprint"]),
            text(node["reason"]),
            node["eligible"].asBoolean(),
            integer(node["estimatedMetadataBytes"]),
            optional(node["originalFile"]),
            array(node["splitFiles"]).map(::text),
            array(node["createdFiles"]).map {
                keys(it, "id", "file", "size", "status")
                CleanupFile(integer(it["id"]), text(it["file"]), integer(it["size"]), text(it["status"]))
            },
        )
    }

    private fun keys(
        node: JsonNode,
        vararg names: String,
    ) {
        require(
            node.isObject && node.properties().map { it.key }.toSet() == names.toSet(),
        )
    }

    private fun array(node: JsonNode): List<JsonNode> {
        require(node.isArray && node.size() <= 10000)
        return node.toList()
    }

    private fun text(node: JsonNode): String {
        require(node.isString)
        return node.asString()
    }

    private fun optional(node: JsonNode): String? = if (node.isNull) null else text(node)

    private fun integer(node: JsonNode): Long {
        require(node.isIntegralNumber && node.canConvertToLong())
        return node.asLong()
    }
}
