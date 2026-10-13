package me.pinfort.tsvideos.core.component

import me.pinfort.tsvideos.core.domain.CallbackInputManifest
import me.pinfort.tsvideos.core.domain.CallbackOutput
import me.pinfort.tsvideos.core.domain.CheckpointEvidence
import me.pinfort.tsvideos.core.domain.LocalCheckpointFile
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Internal immutable database evidence, never an imported execution plan. */
object ProcessingRecoveryJson {
    private val mapper = JsonMapper.builder().build()

    fun write(value: Any): String = mapper.writeValueAsString(value)

    fun evidence(value: String): CheckpointEvidence {
        val n = mapper.readTree(value)
        return CheckpointEvidence(
            n["files"].toList().map(
                ::file,
            ),
            long(n["referenceId"]),
            long(n["drops"])?.toInt(),
            n["duration"]
                .takeUnless {
                    it.isNull
                }?.asDouble(),
        )
    }

    fun callback(value: String): CallbackInputManifest {
        val n = mapper.readTree(value)
        return CallbackInputManifest(
            n["jobId"].asLong(),
            n["itemId"].asInt(),
            n["splitId"].asLong(),
            n["inPath"].asString(),
            n["outputs"].toList().map {
                CallbackOutput(file(it["file"]), it["target"].asString(), text(it["mime"]), text(it["encoding"]))
            },
            n["cleanupFiles"].toList().map(::file),
        )
    }

    private fun long(n: JsonNode): Long? = n.takeUnless { it.isNull }?.asLong()

    private fun text(n: JsonNode): String? = n.takeUnless { it.isNull }?.asString()

    private fun file(n: JsonNode) =
        LocalCheckpointFile(n["path"].asString(), n["size"].asLong(), n["modified"].asLong(), n["sha256"].asString(), long(n["rowId"]))
}
