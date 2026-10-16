package me.pinfort.tsvideos.manager.console.component

import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.ExecutedFile
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramDetail

/** Explicit fields keep the public schema independent of domain helper properties. */
object ReadJson {
    fun program(program: Program): Map<String, Any?> =
        with(program) {
            linkedMapOf(
                "id" to id,
                "name" to name,
                "executedFileId" to executedFileId,
                "status" to status.name,
                "drops" to drops,
                "size" to size,
                "recordedAt" to recordedAt.toString(),
                "channel" to channel,
                "title" to title,
                "channelName" to channelName,
                "duration" to duration,
            )
        }

    fun detail(detail: ProgramDetail): Map<String, Any?> =
        with(detail) {
            program(Program(id, name, executedFileId, status, drops, size, recordedAt, channel, title, channelName, duration)) +
                linkedMapOf(
                    "createdFiles" to createdFiles.map(::createdFile),
                    "tags" to tags,
                    "checks" to checks,
                )
        }

    fun executedFile(executedFile: ExecutedFile): Map<String, Any?> =
        with(executedFile) {
            linkedMapOf(
                "id" to id,
                "file" to file,
                "status" to status.name,
                "drops" to drops,
                "size" to size,
                "recordedAt" to recordedAt.toString(),
                "channel" to channel,
                "title" to title,
                "channelName" to channelName,
                "duration" to duration,
            )
        }

    private fun createdFile(createdFile: CreatedFile): Map<String, Any?> =
        with(createdFile) {
            linkedMapOf(
                "id" to id,
                "splittedFileId" to splittedFileId,
                "file" to file,
                "size" to size,
                "mime" to mime,
                "encoding" to encoding,
                "status" to status.name,
            )
        }
}
