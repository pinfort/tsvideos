package me.pinfort.tsvideos.manager.console.component

import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.ProgramDetail
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Explicit fields keep the public schema independent of domain helper properties. */
object ReadJson {
    fun program(program: Program): Map<String, Any?> =
        with(program) {
            programFields(
                id = id,
                name = name,
                executedFileId = executedFileId,
                status = status,
                drops = drops,
                size = size,
                recordedAt = recordedAt,
                channel = channel,
                title = title,
                channelName = channelName,
                duration = duration,
            )
        }

    fun detail(detail: ProgramDetail): Map<String, Any?> =
        with(detail) {
            programFields(
                id = id,
                name = name,
                executedFileId = executedFileId,
                status = status,
                drops = drops,
                size = size,
                recordedAt = recordedAt,
                channel = channel,
                title = title,
                channelName = channelName,
                duration = duration,
            ) +
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
                "recordedAt" to dateTime(recordedAt),
                "channel" to channel,
                "title" to title,
                "channelName" to channelName,
                "duration" to duration,
            )
        }

    /** Shared by [program] and [detail]; named arguments keep same-typed fields from being swapped. */
    private fun programFields(
        id: Long,
        name: String,
        executedFileId: Long,
        status: Program.Status,
        drops: Int,
        size: Long,
        recordedAt: LocalDateTime,
        channel: String,
        title: String,
        channelName: String,
        duration: Double,
    ): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "name" to name,
            "executedFileId" to executedFileId,
            "status" to status.name,
            "drops" to drops,
            "size" to size,
            "recordedAt" to dateTime(recordedAt),
            "channel" to channel,
            "title" to title,
            "channelName" to channelName,
            "duration" to duration,
        )

    /** LocalDateTime.toString() drops zero seconds; the schema always carries them. */
    private fun dateTime(value: LocalDateTime): String = value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

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
                "width" to width,
                "height" to height,
            )
        }
}
