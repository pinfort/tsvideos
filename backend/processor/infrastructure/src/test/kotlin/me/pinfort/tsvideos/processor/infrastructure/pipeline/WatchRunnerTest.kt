package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.command.ProcessingJobCommand
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.core.domain.ProcessingJob
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WatchRunnerTest :
    ExpectSpec({
        fun ready(
            directory: File,
            name: String,
        ): File =
            File(directory, name).apply {
                parentFile.mkdirs()
                writeText("recording")
                File(path + ".ready").writeText("done")
                Files.setLastModifiedTime(File(path + ".ready").toPath(), FileTime.fromMillis(lastModified() + 1000))
            }
        expect("preexisting uppercase extensions and new markers are emitted once while stale markers wait; dry run has no collaborators") {
            val root = Files.createTempDirectory("watch-existing").toFile()
            val first = ready(root, "a.M2TS")
            val stale = ready(root, "stale.m2ts")
            Files.setLastModifiedTime(File(stale.path + ".ready").toPath(), FileTime.fromMillis(stale.lastModified() - 1000))
            val recordings = mockk<PathProcessingRunner>()
            val jobs = mockk<ProcessingJobCommand>()
            val cancellation = ProcessingCancellation()
            val events = CopyOnWriteArrayList<WatchRunner.Event>()
            val firstReady = CountDownLatch(1)
            val secondReady = CountDownLatch(1)
            var scans = 0
            try {
                shouldThrow<InterruptedException> {
                    WatchRunner(recordings, jobs, ProcessingConcurrency(), cancellation).run(
                        WatchOptions(listOf(root.toPath()), "marker"),
                        true,
                        {
                            events.add(it)
                            when {
                                it.path == first.canonicalPath -> firstReady.countDown()
                                it.code == "WATCH_READY_DRY_RUN" -> secondReady.countDown()
                            }
                        },
                        pause = {
                            scans++
                            if (scans == 1) {
                                firstReady.await(5, TimeUnit.SECONDS) shouldBe true
                                ready(root, "new.m2ts")
                            }
                            if (scans ==
                                3
                            ) {
                                secondReady.await(5, TimeUnit.SECONDS) shouldBe true
                                cancellation.requestStop()
                                cancellation.check()
                            }
                        },
                    )
                }
                events.count { it.code == "WATCH_READY_DRY_RUN" } shouldBe 2
                first.exists() shouldBe true
                File(first.path + ".ready").exists() shouldBe true
                verify(exactly = 0) { jobs.find(any()) }
                verify(exactly = 0) { recordings.processRecording(any(), any(), any(), any()) }
            } finally {
                Thread.interrupted()
                root.deleteRecursively()
            }
        }
        expect("an inaccessible directory backs off independently and recovers while healthy input proceeds") {
            val root = Files.createTempDirectory("watch-recovery").toFile()
            val healthy = File(root, "healthy").apply { mkdirs() }
            val missing = File(root, "missing")
            ready(healthy, "a.m2ts")
            val cancellation = ProcessingCancellation()
            val events = CopyOnWriteArrayList<WatchRunner.Event>()
            val emitted = CountDownLatch(2)
            var now = 0L
            var scans = 0
            try {
                shouldThrow<InterruptedException> {
                    WatchRunner(mockk(), mockk(), ProcessingConcurrency(), cancellation).run(
                        WatchOptions(listOf(missing.toPath(), healthy.toPath()), "marker"),
                        true,
                        {
                            events.add(it)
                            if (it.code == "WATCH_READY_DRY_RUN") emitted.countDown()
                        },
                        clock = { now },
                        pause = {
                            scans++
                            now += 3_000_000_000L
                            if (scans == 1) {
                                ready(missing, "b.m2ts")
                            } else {
                                emitted.await(5, TimeUnit.SECONDS) shouldBe true
                                cancellation.requestStop()
                                cancellation.check()
                            }
                        },
                    )
                }
                events.count { it.code == "WATCH_DIRECTORY_UNAVAILABLE" } shouldBe 1
                events.count { it.code == "WATCH_DIRECTORY_RECOVERED" } shouldBe 1
            } finally {
                Thread.interrupted()
                root.deleteRecursively()
            }
        }
        expect("queued input is fingerprint checked again before any job lookup") {
            val root = Files.createTempDirectory("watch-changed-queue").toFile()
            val files = listOf(ready(root, "a.m2ts"), ready(root, "b.m2ts"))
            val recordings = mockk<PathProcessingRunner>()
            val jobs = mockk<ProcessingJobCommand>(relaxed = true)
            every { jobs.find(any()) } returns null
            every { jobs.findByPath(any()) } returns null
            val cancellation = ProcessingCancellation()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val changed = CountDownLatch(1)
            val active =
                java.util.concurrent.atomic
                    .AtomicReference<File>()
            every { recordings.processRecording(any(), any(), any(), any()) } answers {
                active.set(firstArg())
                entered.countDown()
                release.await()
                PathProcessingRunner.Completion(firstArg<File>().canonicalPath, "PROCESSED")
            }
            try {
                shouldThrow<InterruptedException> {
                    WatchRunner(recordings, jobs, ProcessingConcurrency(workers = 1, queueCapacity = 1), cancellation).run(
                        WatchOptions(listOf(root.toPath()), "marker"),
                        event = {
                            if (it.code ==
                                "WATCH_INPUT_CHANGED"
                            ) {
                                changed.countDown()
                            }
                        },
                        pause = {
                            entered.await(5, TimeUnit.SECONDS) shouldBe true
                            files.first { it != active.get() }.appendText("changed")
                            release.countDown()
                            changed.await(5, TimeUnit.SECONDS) shouldBe true
                            cancellation.requestStop()
                            cancellation.check()
                        },
                    )
                }
                verify(exactly = 1) { recordings.processRecording(any(), any(), any(), any()) }
                verify(exactly = 1) { jobs.find(any()) }
            } finally {
                release.countDown()
                Thread.interrupted()
                root.deleteRecursively()
            }
        }
        expect("stable mode works without markers and ignores nested recordings") {
            val root = Files.createTempDirectory("watch-stable").toFile()
            File(root, "a.m2ts").writeText("recording")
            ready(File(root, "nested"), "b.m2ts")
            val cancellation = ProcessingCancellation()
            val emitted = CountDownLatch(1)
            val events = CopyOnWriteArrayList<WatchRunner.Event>()
            var now = 0L
            try {
                shouldThrow<InterruptedException> {
                    WatchRunner(mockk(), mockk(), ProcessingConcurrency(), cancellation).run(
                        WatchOptions(listOf(root.toPath()), "stable", stableSeconds = 1),
                        true,
                        {
                            events.add(it)
                            emitted.countDown()
                        },
                        clock = { now },
                        pause = {
                            now += 1_000_000_000L
                            if (now >= 2_000_000_000L) {
                                emitted.await(5, TimeUnit.SECONDS) shouldBe true
                                cancellation.requestStop()
                                cancellation.check()
                            }
                        },
                    )
                }
                events.count { it.code == "WATCH_READY_DRY_RUN" } shouldBe 1
            } finally {
                Thread.interrupted()
                root.deleteRecursively()
            }
        }
        expect("directories and symlinks cannot masquerade as completion markers or escape the watched root") {
            val root = Files.createTempDirectory("watch-links").toFile()
            val outside = Files.createTempDirectory("watch-outside").toFile()
            val valid = ready(root, "valid.m2ts")
            val invalid = File(root, "directory-marker.m2ts").apply { writeText("recording") }
            File(invalid.path + ".ready").mkdir()
            if (File.separatorChar == '/') {
                val target = ready(outside, "outside.m2ts")
                Files.createSymbolicLink(File(root, "escape.m2ts").toPath(), target.toPath())
                File(root, "escape.m2ts.ready").writeText("done")
                val linkedMarker = File(root, "linked-marker.m2ts").apply { writeText("recording") }
                Files.createSymbolicLink(File(linkedMarker.path + ".ready").toPath(), File(target.path + ".ready").toPath())
            }
            val cancellation = ProcessingCancellation()
            val emitted = CountDownLatch(1)
            val events = CopyOnWriteArrayList<WatchRunner.Event>()
            try {
                shouldThrow<InterruptedException> {
                    WatchRunner(mockk(), mockk(), ProcessingConcurrency(), cancellation).run(
                        WatchOptions(listOf(root.toPath()), "marker"),
                        true,
                        {
                            events.add(it)
                            emitted.countDown()
                        },
                        pause = {
                            emitted.await(5, TimeUnit.SECONDS) shouldBe true
                            cancellation.requestStop()
                            cancellation.check()
                        },
                    )
                }
                events.filter { it.code == "WATCH_READY_DRY_RUN" }.map { it.path } shouldBe listOf(valid.canonicalPath)
            } finally {
                Thread.interrupted()
                root.deleteRecursively()
                outside.deleteRecursively()
            }
        }
        expect("failure before claim is diagnosed once without exposing exception text or automatic retries") {
            val root = Files.createTempDirectory("watch-db-failure").toFile()
            ready(root, "a.m2ts")
            val jobs = mockk<ProcessingJobCommand>()
            every { jobs.find(any()) } throws IllegalStateException("credential-secret")
            val cancellation = ProcessingCancellation()
            val failure = CountDownLatch(1)
            val events = CopyOnWriteArrayList<WatchRunner.Event>()
            var scans = 0
            try {
                shouldThrow<InterruptedException> {
                    WatchRunner(
                        mockk(),
                        jobs,
                        ProcessingConcurrency(),
                        cancellation,
                    ).run(WatchOptions(listOf(root.toPath()), "marker"), event = {
                        events.add(it)
                        failure.countDown()
                    }, pause = {
                        scans++
                        if (scans == 3) {
                            failure.await(5, TimeUnit.SECONDS) shouldBe true
                            cancellation.requestStop()
                            cancellation.check()
                        }
                    })
                }
                events.map { it.code } shouldBe listOf("WATCH_OPERATION_FAILED")
                verify(exactly = 1) { jobs.find(any()) }
            } finally {
                Thread.interrupted()
                root.deleteRecursively()
            }
        }
        expect("capacity exhaustion is explicit and drains accepted queued recordings before exit4") {
            val root = Files.createTempDirectory("watch-capacity").toFile()
            repeat(3) { ready(root, "$it.m2ts") }
            val recordings = mockk<PathProcessingRunner>()
            val jobs = mockk<ProcessingJobCommand>(relaxed = true)
            every { jobs.find(any()) } returns null
            every { jobs.findByPath(any()) } returns null
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val capacity = CountDownLatch(1)
            every { recordings.processRecording(any(), any(), any(), any()) } answers {
                entered.countDown()
                release.await()
                PathProcessingRunner.Completion(firstArg<File>().canonicalPath, "PROCESSED")
            }
            var result = -1
            val worker =
                Thread {
                    result =
                        WatchRunner(recordings, jobs, ProcessingConcurrency(workers = 1, queueCapacity = 1), ProcessingCancellation())
                            .run(WatchOptions(listOf(root.toPath()), "marker", capacity = 2), event = {
                                if (it.code ==
                                    "WATCH_CAPACITY_EXCEEDED"
                                ) {
                                    capacity.countDown()
                                }
                            })
                }.apply { start() }
            try {
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                capacity.await(5, TimeUnit.SECONDS) shouldBe true
                worker.isAlive shouldBe true
            } finally {
                release.countDown()
                worker.join(5000)
            }
            result shouldBe 4
            verify(exactly = 2) { recordings.processRecording(any(), any(), any(), any()) }
            root.deleteRecursively()
        }
        expect("durable existing jobs including retryable and unknown suppress work across watch restarts") {
            val root = Files.createTempDirectory("watch-restart").toFile()
            ready(root, "a.m2ts")
            val recordings = mockk<PathProcessingRunner>()
            val jobs = mockk<ProcessingJobCommand>()
            try {
                listOf("RETRYABLE", "SUBMISSION_UNKNOWN", "COMPLETED").forEach { state ->
                    every { jobs.find(any()) } returns ProcessingJob(1, "key", "path", 1, 1, state, null, null)
                    val cancellation = ProcessingCancellation()
                    val seen = CountDownLatch(1)
                    shouldThrow<InterruptedException> {
                        WatchRunner(
                            recordings,
                            jobs,
                            ProcessingConcurrency(),
                            cancellation,
                        ).run(WatchOptions(listOf(root.toPath()), "marker"), event = {
                            seen.countDown()
                        }, pause = {
                            seen.await(5, TimeUnit.SECONDS) shouldBe true
                            cancellation.requestStop()
                            cancellation.check()
                        })
                    }
                }
                verify(exactly = 0) { recordings.processRecording(any(), any(), any(), any()) }
            } finally {
                Thread.interrupted()
                root.deleteRecursively()
            }
        }
    })
