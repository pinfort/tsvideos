package me.pinfort.tsvideos.console.common.version

import java.util.Properties

/**
 * ビルド時に生成される version.properties から読み出したアプリケーションのバージョン情報。
 * 両 CLI の `--version` が使う (生成は console-common の generateVersionProperties)。
 */
object ApplicationVersion {
    private const val RESOURCE_PATH = "me/pinfort/tsvideos/console/common/version.properties"
    private const val UNKNOWN = "unknown"

    private val properties = read()

    /** Gradleプロジェクトのバージョン。 */
    val version: String = properties.value("version")

    /** ビルド時のgitコミットハッシュ。gitリポジトリ外でビルドした場合は "unknown"。 */
    val commit: String = properties.value("commit")

    /** `--version` で表示する文字列。 */
    val value: String = if (commit == UNKNOWN) version else "$version ($commit)"

    private fun read(): Properties {
        val stream =
            javaClass.classLoader.getResourceAsStream(RESOURCE_PATH)
                ?: return Properties()
        return stream.use { Properties().apply { load(it) } }
    }

    private fun Properties.value(key: String): String = getProperty(key)?.takeIf(String::isNotBlank) ?: UNKNOWN
}
