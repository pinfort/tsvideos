package me.pinfort.tsvideos.core.component

import org.springframework.stereotype.Component

/**
 * ファイル名から mime / encoding を判定する。
 * Files.probeContentType はプラットフォーム依存で Windows では期待した値を返さないため、
 * 扱う拡張子を明示したテーブルで解決する。
 * mime の文字列は CreatedFile.isMp4 / CreatedFile.isTs と一致している必要がある。
 */
@Component
class MimeTypeComponent {
    data class MimeType(
        val mime: String?,
        val encoding: String?,
    ) {
        /** An uncompressed video that a media probe (ffprobe) can read directly; a compressed (e.g. gzip) file cannot be. */
        val isProbeableVideo: Boolean get() = mime?.startsWith("video/") == true && encoding == null
    }

    companion object {
        private const val GZIP_SUFFIX = ".gz"

        private val MIME_BY_EXTENSION =
            mapOf(
                "mp4" to "video/mp4",
                "m2ts" to "video/vnd.dlna.mpeg-tts",
                "ts" to "video/vnd.dlna.mpeg-tts",
                // Amatsukaze が動画と一緒に出力する字幕・補助ファイル。video/ 以外なので統計・検索では動画として数えられない。
                // 追加・変更したら backend/docs/verified-uploads.md の埋め戻し SQL も合わせて更新する。
                "ass" to "text/x-ssa",
                "srt" to "application/x-subrip",
                "txt" to "text/plain",
                "log" to "text/plain",
                "json" to "application/json",
                "xml" to "application/xml",
                "aac" to "audio/aac",
                "m4a" to "audio/mp4",
                "wav" to "audio/wav",
            )
    }

    fun guess(fileName: String): MimeType {
        val lowered = fileName.lowercase()
        return if (lowered.endsWith(GZIP_SUFFIX)) {
            MimeType(mimeOf(lowered.removeSuffix(GZIP_SUFFIX)), "gzip")
        } else {
            MimeType(mimeOf(lowered), null)
        }
    }

    private fun mimeOf(fileName: String): String? = MIME_BY_EXTENSION[fileName.substringAfterLast('.', "")]
}
