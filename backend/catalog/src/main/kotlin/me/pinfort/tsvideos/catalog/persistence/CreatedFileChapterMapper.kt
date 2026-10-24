package me.pinfort.tsvideos.catalog.persistence

import me.pinfort.tsvideos.catalog.domain.VideoChapter
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/** `created_file_chapter` rows and the `created_file.chapters_probed` flag that says whether they were read. */
@Mapper
internal interface CreatedFileChapterMapper {
    @Select("SELECT chapters_probed FROM created_file WHERE id = #{createdFileId}")
    fun chaptersProbed(createdFileId: Long): Boolean?

    /** The row lock taken here serializes concurrent recorders: only the one that flips the flag inserts chapters. */
    @Update("UPDATE created_file SET chapters_probed = TRUE WHERE id = #{createdFileId} AND chapters_probed = FALSE")
    fun markProbedIfMissing(createdFileId: Long): Int

    @Insert(
        """
        <script>
            INSERT INTO created_file_chapter(created_file_id, chapter_index, start_seconds, end_seconds, title)
            VALUES
            <foreach item="chapter" collection="chapters" separator=",">
                (#{createdFileId}, #{chapter.index}, #{chapter.startSeconds}, #{chapter.endSeconds}, #{chapter.title})
            </foreach>
        </script>
        """,
    )
    fun insert(
        @Param("createdFileId") createdFileId: Long,
        @Param("chapters") chapters: List<VideoChapter>,
    ): Int

    @Select(
        """
            SELECT
                chapter_index,
                start_seconds,
                end_seconds,
                title
            FROM
                created_file_chapter
            WHERE
                created_file_id = #{createdFileId}
            ORDER BY
                chapter_index
        """,
    )
    fun select(createdFileId: Long): List<CreatedFileChapterDto>

    @Select(
        """
            SELECT
                id,
                splitted_file_id,
                file,
                size,
                mime,
                encoding,
                status,
                width,
                height
            FROM
                created_file
            WHERE
                mime = 'video/mp4'
                AND chapters_probed = FALSE
                AND id > #{afterId}
            ORDER BY
                id
            LIMIT #{limit}
        """,
    )
    fun selectUnprobedVideos(
        afterId: Long,
        limit: Int,
    ): List<CreatedFileDto>
}

internal data class CreatedFileChapterDto(
    val chapterIndex: Int,
    val startSeconds: Double,
    val endSeconds: Double,
    val title: String?,
) {
    fun toDomain(): VideoChapter = VideoChapter(chapterIndex, startSeconds, endSeconds, title)
}
