package me.pinfort.tsvideos.catalog.domain

data class CreatedFile(
    val id: Long,
    val splittedFileId: Long,
    val file: String,
    val size: Long,
    val mime: String?,
    val encoding: String?,
    val status: Status,
    val width: Int? = null,
    val height: Int? = null,
) {
    enum class Status {
        REGISTERED,
        ENCODE_SUCCESS,
        FILE_MOVED,
    }

    val isMp4 = mime == MP4_MIME

    val isTs = mime == TS_MIME

    companion object {
        const val MP4_MIME = "video/mp4"
        const val TS_MIME = "video/vnd.dlna.mpeg-tts"
        const val GZIP_ENCODING = "gzip"
    }
}
