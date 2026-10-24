package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.processing.api.ChapterBackfillRunner
import me.pinfort.tsvideos.processing.api.ChapterBackfillRunner.FileResult
import me.pinfort.tsvideos.processing.api.ChapterBackfillRunner.Outcome
import me.pinfort.tsvideos.processor.console.parseProcessor
import java.nio.file.Files
import java.nio.file.Path

class ChaptersCommandTest :
    ExpectSpec({
        val root = Files.createTempDirectory("chapters-command-")
        afterSpec { Files.deleteIfExists(root) }

        fun command(
            vararg results: FileResult,
            runner: ChapterBackfillRunner = mockk(),
        ): ChaptersCommand {
            every { runner.run(any(), any(), any()) } answers {
                val report = thirdArg<(FileResult) -> Unit>()
                results.fold(ChapterBackfillRunner.Summary()) { summary, result ->
                    report(result)
                    summary + result.outcome
                }
            }
            return ChaptersCommand(ChaptersBackfillCommand(runner))
        }

        fun result(
            id: Long,
            outcome: Outcome,
            chapters: Int? = null,
        ) = FileResult(id, "videos/$id.mp4", Path.of("share", "videos", "$id.mp4"), outcome, chapters)

        expect("reports each file and the counts, and exits 0 when everything was probed") {
            val runner = mockk<ChapterBackfillRunner>()
            val cli = command(result(1, Outcome.CHAPTERS, 12), result(2, Outcome.NO_CHAPTERS, 0), runner = runner)

            val output = cli.test(listOf("backfill", "--video-store-root", root.toString()))

            output.statusCode shouldBe 0
            output.stdout shouldContain "recorded 12 chapter(s): created_file 1"
            output.stdout shouldContain "recorded no chapters: created_file 2"
            output.stdout shouldContain "probed: 1 with chapters, 1 without chapters, 0 already probed; ffprobe failed: 0"
            verify { runner.run(root, false, any()) }
        }

        expect("a dry run says what it would record") {
            val runner = mockk<ChapterBackfillRunner>()
            val cli = command(result(1, Outcome.CHAPTERS, 3), runner = runner)

            val output = cli.test(listOf("backfill", "--video-store-root", root.toString(), "-d"))

            output.statusCode shouldBe 0
            output.stdout shouldContain "would record 3 chapter(s): created_file 1"
            output.stdout shouldContain "(dry run: nothing recorded)"
            verify { runner.run(root, true, any()) }
        }

        expect("skipped and failed files are reported and make the command exit 1") {
            val cli =
                command(
                    result(1, Outcome.CHAPTERS, 2),
                    result(2, Outcome.MISSING),
                    result(3, Outcome.SIZE_MISMATCH),
                    result(4, Outcome.PROBE_FAILED),
                    result(5, Outcome.INVALID_PATH),
                )

            val output = cli.test(listOf("backfill", "--video-store-root", root.toString()))

            output.statusCode shouldBe 1
            output.stdout shouldContain "file missing: created_file 2"
            output.stdout shouldContain "size differs from the recorded size: created_file 3"
            output.stdout shouldContain "ffprobe failed: created_file 4"
            output.stdout shouldContain "path cannot be joined under the root: created_file 5"
            output.stdout shouldContain "ffprobe failed: 1; missing/size mismatch: 1/1; invalid path: 1"
            output.stderr shouldContain "4 file(s) skipped or failed"
        }

        expect("a root that does not exist is a usage error and runs nothing") {
            val runner = mockk<ChapterBackfillRunner>()
            val cli = command(runner = runner)

            val output = cli.test(listOf("backfill", "--video-store-root", root.resolve("absent").toString())) { cli.parseProcessor(it) }

            output.statusCode shouldBe 2
            verify(exactly = 0) { runner.run(any(), any(), any()) }
        }

        expect("the root is required") {
            val output = command().test(listOf("backfill"))
            output.statusCode shouldBe 1
            output.stderr shouldContain "--video-store-root"
        }

        expect("an interruption shows the partial counts and propagates") {
            val runner = mockk<ChapterBackfillRunner>()
            every { runner.run(any(), any(), any()) } answers {
                thirdArg<(FileResult) -> Unit>()(result(1, Outcome.CHAPTERS, 2))
                throw InterruptedException("stop")
            }
            val cli = ChaptersCommand(ChaptersBackfillCommand(runner))

            shouldThrow<InterruptedException> {
                cli.parseProcessor(listOf("backfill", "--video-store-root", root.toString()))
            }
            Thread.interrupted()
        }

        expect("help names the share root option") {
            command().test(listOf("backfill", "--help")).stdout.let {
                it shouldContain "--video-store-root"
                it shouldNotContain "Error"
            }
        }
    })
