package me.pinfort.tsvideos.catalog.api

import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.name

@Component
class DirectoryNameComponent(
    private val normalizeComponent: NormalizeComponent,
) {
    fun indexDirectoryName(path: Path): String = programDirectoryName(path).take(1)

    fun programDirectoryName(path: Path): String = normalizeComponent.normalize(path.parent.name)

    /**
     * @param path 現在のフルパス ex. example/f/foo/bar.m2ts
     * @param directoryName 置き換えるディレクトリ名 ex. hoge
     *
     * @return 置き換えたフルパス ex. example/h/hoge/bar.m2ts
     */
    fun replaceWithGivenDirectoryName(
        path: Path,
        directoryName: String,
    ): Path {
        val normalizedProgramDirectoryName = normalizeComponent.normalize(directoryName)
        val normalizedIndexDirectoryName = normalizedProgramDirectoryName.take(1)

        val baseName = path.parent.parent.parent
        val fileName = path.fileName
        return baseName.resolve(normalizedIndexDirectoryName).resolve(normalizedProgramDirectoryName).resolve(fileName)
    }
}
