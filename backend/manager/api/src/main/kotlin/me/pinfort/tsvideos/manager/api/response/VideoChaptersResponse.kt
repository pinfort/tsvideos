package me.pinfort.tsvideos.manager.api.response

import me.pinfort.tsvideos.catalog.domain.VideoChapters

/** `probed` false means the chapters were never read, so the empty list then says nothing about the file. */
data class VideoChaptersResponse(
    val probed: Boolean,
    val chapters: List<Chapter>,
) {
    data class Chapter(
        val index: Int,
        val startSeconds: Double,
        val endSeconds: Double,
        val title: String?,
    )

    companion object {
        fun of(chapters: VideoChapters): VideoChaptersResponse =
            VideoChaptersResponse(
                probed = chapters.probed,
                chapters = chapters.chapters.map { Chapter(it.index, it.startSeconds, it.endSeconds, it.title) },
            )
    }
}
