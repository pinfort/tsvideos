package me.pinfort.tsvideos.processing.api

import java.nio.file.Files
import java.nio.file.Path

/**
 * Expand a processing target the same way for preflight and the pipeline: a directory yields its
 * regular `.m2ts` children (non-recursive, case-sensitive suffix, filename order); anything else is
 * returned as is. Throws when a directory cannot be listed.
 */
fun expandRecordingInputs(path: Path): List<Path> =
    if (Files.isDirectory(path)) {
        Files.list(path).use { entries ->
            entries
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".m2ts") }
                .sorted(compareBy { it.fileName.toString() })
                .toList()
        }
    } else {
        listOf(path)
    }
