package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.component.ProcessingCancellation
import me.pinfort.tsvideos.processor.infrastructure.external.slack.SlackClient
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ParallelPathProcessingTest :
    ExpectSpec({
        expect("two workers overlap, failures are isolated, and progress retains each source path") {
            val root = Files.createTempDirectory("parallel-path").toFile()
            val inputs = (1..4).map { root.resolve("$it.m2ts").apply { writeText("recording") } }
            val entered = CountDownLatch(2)
            val pipeline = mockk<FileProcessingPipeline>()
            val slack = mockk<SlackClient>(relaxed = true)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val events = java.util.concurrent.ConcurrentLinkedQueue<PathProcessingRunner.Progress>()
            every { pipeline.processFile(any(), any(), any(), any(), any()) } answers {
                val file = firstArg<java.io.File>()
                val current = active.incrementAndGet()
                maximum.accumulateAndGet(current, ::maxOf)
                try {
                    entered.countDown()
                    check(entered.await(5, TimeUnit.SECONDS))
                    arg<(Long, Long) -> Unit>(2)(1, 2)
                    if (file.name == "1.m2ts") error("credential-secret")
                    FileProcessingPipeline.Result.PROCESSED
                } finally {
                    active.decrementAndGet()
                }
            }
            try {
                val runner =
                    PathProcessingRunner(
                        pipeline,
                        slack,
                        mockk(relaxed = true),
                        ProcessingCancellation(),
                        ProcessingConcurrency(workers = 2, queueCapacity = 1),
                    )
                val result = runner.processPaths(listOf(root.toPath()), progress = { events.add(it) })
                result shouldBe PathProcessingRunner.BatchResult(3, 1)
                maximum.get() shouldBe 2
                events.map { it.path }.toSet() shouldBe inputs.map { it.canonicalPath }.toSet()
                verify(exactly = 1) { slack.notify(match { !it.contains("credential-secret") }) }
            } finally {
                root.deleteRecursively()
            }
        }
        expect("stop interrupts every owner and queued recordings never enter the pipeline") {
            val root = Files.createTempDirectory("parallel-stop").toFile()
            repeat(10) { root.resolve("$it.m2ts").writeText("recording") }
            val cancellation = ProcessingCancellation()
            val pipeline = mockk<FileProcessingPipeline>()
            val entered = CountDownLatch(2)
            val calls = AtomicInteger()
            every { pipeline.processFile(any(), any(), any(), any(), any()) } answers {
                calls.incrementAndGet()
                entered.countDown()
                CountDownLatch(1).await()
                FileProcessingPipeline.Result.PROCESSED
            }
            val runner =
                PathProcessingRunner(
                    pipeline,
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    cancellation,
                    ProcessingConcurrency(workers = 2, queueCapacity = 1),
                )
            val failure = AtomicReference<Throwable>()
            val producer =
                Thread {
                    try {
                        runner.processPaths(listOf(root.toPath()))
                    } catch (caught: Throwable) {
                        failure.set(caught)
                    }
                }.apply { start() }
            try {
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                cancellation.requestStop()
                producer.join(5000)
                producer.isAlive shouldBe false
                cancellation.awaitStopped(5000) shouldBe true
                calls.get() shouldBe 2
                ProcessingCancellation.isInterrupted(checkNotNull(failure.get())) shouldBe true
            } finally {
                cancellation.requestStop()
                producer.join(5000)
                root.deleteRecursively()
            }
        }
    })
