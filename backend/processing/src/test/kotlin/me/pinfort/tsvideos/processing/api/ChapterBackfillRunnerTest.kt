package me.pinfort.tsvideos.processing.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.VideoChapter
import me.pinfort.tsvideos.processing.api.ChapterBackfillRunner.Outcome
import me.pinfort.tsvideos.processing.api.tool.ChapterProbeClient
import org.slf4j.Logger
import org.springframework.transaction.PlatformTransactionManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class ChapterBackfillRunnerTest :
    ExpectSpec({
        val isWindows = File.separatorChar == '\\'
        val chapters = listOf(VideoChapter(0, 0.0, 5.739, "A"), VideoChapter(1, 5.739, 30.0, "CM"))

        class Fixture {
            val root: Path = Files.createTempDirectory("chapter-backfill-")
            val catalog = mockk<CreatedFileCatalog>()
            val probe = mockk<ChapterProbeClient>()
            val transactions = mockk<PlatformTransactionManager>(relaxed = true)
            val cancellation = ProcessingCancellation()
            val runner = ChapterBackfillRunner(catalog, probe, transactions, mockk<Logger>(relaxed = true), cancellation)
            val results = mutableListOf<ChapterBackfillRunner.FileResult>()

            init {
                every { probe.probe(any()) } returns chapters
                every { catalog.recordChaptersIfMissing(any(), any()) } returns true
            }

            /** A share file at [relative] with [bytes] bytes, registered with [recordedSize] (default: its real size). */
            fun video(
                id: Long,
                relative: String,
                bytes: Int? = 10,
                recordedSize: Long? = null,
            ): CreatedFile {
                if (bytes != null) {
                    val local = root.resolve(relative.replace('/', File.separatorChar))
                    Files.createDirectories(local.parent)
                    Files.write(local, ByteArray(bytes))
                }
                return CreatedFile(id, 1, relative, recordedSize ?: bytes?.toLong() ?: 10, "video/mp4", null, CreatedFile.Status.FILE_MOVED)
            }

            fun rows(vararg files: CreatedFile) {
                every { catalog.videosWithoutChapters(0, 100) } returns files.toList()
                every { catalog.videosWithoutChapters(files.last().id, 100) } returns emptyList()
            }

            fun run(dryRun: Boolean = false) = runner.run(root, dryRun) { results += it }

            fun local(relative: String): File = root.resolve(relative.replace('/', File.separatorChar)).toFile()

            fun cleanup() {
                root.toFile().deleteRecursively()
            }
        }

        expect("records the chapters of each share file in its own transaction and counts the outcomes") {
            val f = Fixture()
            try {
                f.rows(f.video(1, "videos/a/first.mp4"), f.video(2, "videos/b/second.mp4"))
                every { f.probe.probe(f.local("videos/b/second.mp4")) } returns emptyList()

                val summary = f.run()

                summary[Outcome.CHAPTERS] shouldBe 1
                summary[Outcome.NO_CHAPTERS] shouldBe 1
                summary.failed shouldBe 0
                f.results.map { it.outcome to it.chapters } shouldBe listOf(Outcome.CHAPTERS to 2, Outcome.NO_CHAPTERS to 0)
                f.results[0].localPath shouldBe
                    f
                        .local("videos/a/first.mp4")
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                verify { f.probe.probe(f.local("videos/a/first.mp4")) }
                verify { f.catalog.recordChaptersIfMissing(1, chapters) }
                verify { f.catalog.recordChaptersIfMissing(2, emptyList()) }
                verify(exactly = 2) { f.transactions.getTransaction(any()) }
                verify(exactly = 2) { f.transactions.commit(any()) }
            } finally {
                f.cleanup()
            }
        }

        expect("a dry run probes and reports what it found but writes nothing") {
            val f = Fixture()
            try {
                f.rows(f.video(1, "videos/first.mp4"))

                val summary = f.run(dryRun = true)

                summary[Outcome.CHAPTERS] shouldBe 1
                f.results.single().chapters shouldBe 2
                verify(exactly = 1) { f.probe.probe(any()) }
                verify(exactly = 0) { f.catalog.recordChaptersIfMissing(any(), any()) }
                verify(exactly = 0) { f.transactions.getTransaction(any()) }
            } finally {
                f.cleanup()
            }
        }

        expect("missing files, size mismatches, unsafe paths and failed probes are skipped, reported and counted as failures") {
            val f = Fixture()
            try {
                f.rows(
                    f.video(1, "videos/missing.mp4", bytes = null),
                    f.video(2, "videos/resized.mp4", bytes = 10, recordedSize = 11),
                    f.video(3, "../outside.mp4", bytes = null),
                    f.video(4, "videos/broken.mp4"),
                    f.video(5, "videos/fine.mp4"),
                )
                every { f.probe.probe(f.local("videos/broken.mp4")) } returns null

                val summary = f.run()

                f.results.map { it.createdFileId to it.outcome } shouldBe
                    listOf(
                        1L to Outcome.MISSING,
                        2L to Outcome.SIZE_MISMATCH,
                        3L to Outcome.INVALID_PATH,
                        4L to Outcome.PROBE_FAILED,
                        5L to Outcome.CHAPTERS,
                    )
                summary.failed shouldBe 4
                verify(exactly = 0) { f.probe.probe(f.local("videos/missing.mp4")) }
                verify(exactly = 0) { f.probe.probe(f.local("videos/resized.mp4")) }
                verify(exactly = 1) { f.catalog.recordChaptersIfMissing(any(), any()) }
                verify { f.catalog.recordChaptersIfMissing(5, chapters) }
            } finally {
                f.cleanup()
            }
        }

        expect("a directory at the joined path is missing, not probed") {
            val f = Fixture()
            try {
                Files.createDirectories(f.root.resolve("directory.mp4"))
                f.rows(f.video(1, "directory.mp4", bytes = null))
                f.run()[Outcome.MISSING] shouldBe 1
                verify(exactly = 0) { f.probe.probe(any()) }
            } finally {
                f.cleanup()
            }
        }

        expect("a row another writer recorded first is counted as already probed, not as a failure") {
            val f = Fixture()
            try {
                f.rows(f.video(1, "videos/first.mp4"))
                every { f.catalog.recordChaptersIfMissing(1, any()) } returns false
                val summary = f.run()
                summary[Outcome.ALREADY_PROBED] shouldBe 1
                summary.failed shouldBe 0
            } finally {
                f.cleanup()
            }
        }

        expect("walks the rows in batches with an id cursor") {
            val f = Fixture()
            try {
                val first = (1L..100L).map { f.video(it, "videos/$it.mp4") }
                val last = f.video(150, "videos/150.mp4")
                every { f.catalog.videosWithoutChapters(0, 100) } returns first
                every { f.catalog.videosWithoutChapters(100, 100) } returns listOf(last)
                every { f.catalog.videosWithoutChapters(150, 100) } returns emptyList()

                f.run()[Outcome.CHAPTERS] shouldBe 101
                verify(exactly = 101) { f.catalog.recordChaptersIfMissing(any(), any()) }
            } finally {
                f.cleanup()
            }
        }

        expect("a stop request ends the run before the next file and keeps what was recorded") {
            val f = Fixture()
            try {
                f.rows(f.video(1, "videos/first.mp4"), f.video(2, "videos/second.mp4"))
                shouldThrow<InterruptedException> {
                    f.runner.run(f.root, false) {
                        f.results += it
                        f.cancellation.requestStop()
                    }
                }
                f.results.map { it.createdFileId } shouldBe listOf(1L)
                verify(exactly = 1) { f.catalog.recordChaptersIfMissing(1, chapters) }
                verify(exactly = 0) { f.probe.probe(f.local("videos/second.mp4")) }
            } finally {
                Thread.interrupted()
                f.cleanup()
            }
        }

        expect("a stop requested while a probe succeeds ends the run without recording that file") {
            val f = Fixture()
            try {
                f.rows(f.video(1, "videos/first.mp4"))
                every { f.probe.probe(any()) } answers {
                    f.cancellation.requestStop()
                    chapters
                }
                shouldThrow<InterruptedException> { f.run() }
                f.results shouldBe emptyList()
                verify(exactly = 1) { f.probe.probe(any()) }
                verify(exactly = 0) { f.catalog.recordChaptersIfMissing(any(), any()) }
                verify(exactly = 0) { f.transactions.getTransaction(any()) }
            } finally {
                Thread.interrupted()
                f.cleanup()
            }
        }

        expect("an interrupted probe propagates without recording") {
            val f = Fixture()
            try {
                f.rows(f.video(1, "videos/first.mp4"))
                every { f.probe.probe(any()) } throws InterruptedException()
                shouldThrow<InterruptedException> { f.run() }
                verify(exactly = 0) { f.catalog.recordChaptersIfMissing(any(), any()) }
            } finally {
                Thread.interrupted()
                f.cleanup()
            }
        }

        expect("refuses a root that is not a directory") {
            val f = Fixture()
            try {
                shouldThrow<IllegalArgumentException> { f.runner.run(f.root.resolve("absent"), false) }
                verify(exactly = 0) { f.catalog.videosWithoutChapters(any(), any()) }
            } finally {
                f.cleanup()
            }
        }

        expect("a link that leads outside the root is not probed") {
            val f = Fixture()
            val outside = Files.createTempFile("chapter-backfill-outside-", ".mp4")
            try {
                Files.write(outside, ByteArray(10))
                val link =
                    try {
                        Files.createSymbolicLink(f.root.resolve("link.mp4"), outside)
                    } catch (_: Exception) {
                        null // Creating links needs a privilege on Windows; the containment check is then not exercised.
                    }
                if (link != null) {
                    f.rows(f.video(1, "link.mp4", bytes = null))
                    f.run()[Outcome.INVALID_PATH] shouldBe 1
                    verify(exactly = 0) { f.probe.probe(any()) }
                }
            } finally {
                Files.deleteIfExists(outside)
                f.cleanup()
            }
        }

        val root = Files.createTempDirectory("chapter-root-")
        afterSpec { root.toFile().deleteRecursively() }

        context("resolveUnder") {
            expect("joins a '/'-separated share path segment by segment under the root") {
                ChapterBackfillRunner.resolveUnder(root, "videos/m/番組 #1/video.mp4") shouldBe
                    root
                        .toAbsolutePath()
                        .resolve("videos")
                        .resolve("m")
                        .resolve("番組 #1")
                        .resolve("video.mp4")
            }

            expect("accepts '\\' as a separator, as SMB does") {
                ChapterBackfillRunner.resolveUnder(root, "videos\\video.mp4") shouldBe
                    root.toAbsolutePath().resolve("videos").resolve("video.mp4")
            }

            expect("rejects paths that are not plain relative share paths") {
                listOf(
                    "",
                    " ",
                    "/videos/a.mp4",
                    "\\videos\\a.mp4",
                    "\\\\nas\\share\\a.mp4",
                    "C:\\videos\\a.mp4",
                    "C:videos/a.mp4",
                    "smb://nas/videos/a.mp4",
                    "videos/a.mp4:stream",
                    "../a.mp4",
                    "videos/../../a.mp4",
                    "videos/./a.mp4",
                    "videos/.../a.mp4",
                    "videos//a.mp4",
                    "videos/",
                    "videos/a\u0000.mp4",
                    "videos/a\n.mp4",
                ).forEach { ChapterBackfillRunner.resolveUnder(root, it) shouldBe null }
            }

            // Windows paths only parse on Windows, where the processor runs next to Amatsukaze.
            expect("joins under a UNC share root and a drive root on Windows") {
                if (!isWindows) return@expect
                ChapterBackfillRunner.resolveUnder(Path.of("\\\\nas\\videos"), "a/b/video.mp4").toString() shouldBe
                    "\\\\nas\\videos\\a\\b\\video.mp4"
                ChapterBackfillRunner.resolveUnder(Path.of("D:\\share"), "a/video.mp4").toString() shouldBe "D:\\share\\a\\video.mp4"
                ChapterBackfillRunner.resolveUnder(Path.of("\\\\nas\\videos"), "a/../../other/video.mp4") shouldBe null
            }
        }
    })
