package me.pinfort.tsvideos.catalog.domain

/**
 * A chapter embedded in an encoded video. [index] is the 0-based position in start order; [title] is stored exactly as
 * the file carries it (join_logo_scp labels such as `A`, `CM`, `CM?` are neither unique nor meaningful) and is null
 * when the chapter has none.
 */
data class VideoChapter(
    val index: Int,
    val startSeconds: Double,
    val endSeconds: Double,
    val title: String?,
)

/**
 * The chapters recorded for one created file. [probed] false means the chapters were never read (no probe yet, or
 * the probe failed), so an empty [chapters] list then says nothing; probed true with no chapters means the file has none.
 */
data class VideoChapters(
    val probed: Boolean,
    val chapters: List<VideoChapter>,
) {
    companion object {
        val NOT_PROBED = VideoChapters(false, emptyList())
    }
}
