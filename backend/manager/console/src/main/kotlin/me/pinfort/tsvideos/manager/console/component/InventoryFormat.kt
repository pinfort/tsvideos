package me.pinfort.tsvideos.manager.console.component

import me.pinfort.tsvideos.core.domain.InventoryExport

object InventoryFormat {
    fun json(inventory: InventoryExport): Map<String, Any?> =
        linkedMapOf(
            "generatedAt" to inventory.generatedAt.toString(),
            "recordingTimestampBasis" to "Recording environment local time; no timezone offset is stored",
            "programs" to
                inventory.programs.map { entry ->
                    linkedMapOf(
                        "program" to
                            with(entry.program) {
                                linkedMapOf(
                                    "id" to id,
                                    "name" to name,
                                    "status" to status.name,
                                    "executedFileId" to executedFileId,
                                )
                            },
                        "executedFile" to entry.executedFile?.let(ReadJson::executedFile),
                        "splittedFiles" to
                            entry.splittedFiles.map {
                                with(it) {
                                    linkedMapOf(
                                        "id" to id,
                                        "executedFileId" to executedFileId,
                                        "file" to file,
                                        "size" to size,
                                        "duration" to duration,
                                        "status" to status.name,
                                    )
                                }
                            },
                        "createdFiles" to
                            entry.createdFiles.map {
                                with(it) {
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
                            },
                    )
                },
        )

    fun csv(inventory: InventoryExport): String {
        val rows =
            mutableListOf<List<Any?>>(
                listOf(
                    "schemaVersion",
                    "generatedAt",
                    "rowType",
                    "programId",
                    "programName",
                    "programStatus",
                    "executedFileId",
                    "recordedPath",
                    "recordedAt",
                    "channel",
                    "title",
                    "channelName",
                    "durationSeconds",
                    "drops",
                    "recordedBytes",
                    "executedStatus",
                    "splittedFileId",
                    "splitPath",
                    "splitBytes",
                    "splitDurationSeconds",
                    "splitStatus",
                    "createdFileId",
                    "createdPath",
                    "createdBytes",
                    "mime",
                    "encoding",
                    "createdStatus",
                ),
            )
        inventory.programs.forEach { entry ->
            val program = entry.program
            val executed = entry.executedFile
            val base =
                listOf(
                    program.id,
                    program.name,
                    program.status.name,
                    program.executedFileId,
                    executed?.file,
                    executed?.recordedAt?.toString(),
                    executed?.channel,
                    executed?.title,
                    executed?.channelName,
                    executed?.duration,
                    executed?.drops,
                    executed?.size,
                    executed?.status?.name,
                )
            val splits = entry.splittedFiles.map { it.id to it }.toMap()
            val writtenSplits = mutableSetOf<Long>()

            fun add(
                kind: String,
                splitId: Long?,
                file: me.pinfort.tsvideos.core.domain.CreatedFile?,
            ) {
                val split = splits[splitId]
                rows.add(
                    listOf(1, inventory.generatedAt.toString(), kind) + base +
                        listOf(
                            splitId,
                            split?.file,
                            split?.size,
                            split?.duration,
                            split?.status?.name,
                            file?.id,
                            file?.file,
                            file?.size,
                            file?.mime,
                            file?.encoding,
                            file?.status?.name,
                        ),
                )
            }
            entry.createdFiles.forEach { file ->
                add("CREATED", file.splittedFileId, file)
                writtenSplits.add(file.splittedFileId)
            }
            entry.splittedFiles.filter { it.id !in writtenSplits }.forEach { add("SPLIT", it.id, null) }
            if (entry.createdFiles.isEmpty() && entry.splittedFiles.isEmpty()) add("PROGRAM", null, null)
        }
        return rows.joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { cell(it) } }
    }

    private fun cell(value: Any?): String {
        var text = value?.toString() ?: ""
        if (value is String &&
            (text.firstOrNull() in listOf('\t', '\r', '\n') || text.trimStart().firstOrNull() in listOf('=', '+', '-', '@'))
        ) {
            text = "'$text"
        }
        return "\"${text.replace("\"", "\"\"")}\""
    }
}
