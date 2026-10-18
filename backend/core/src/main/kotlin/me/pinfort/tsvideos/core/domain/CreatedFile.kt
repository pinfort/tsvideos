package me.pinfort.tsvideos.core.domain

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

    val isMp4 = mime == "video/mp4"

    val isTs = mime == TS_MIME

    companion object {
        const val TS_MIME = "video/vnd.dlna.mpeg-tts"
    }
}
