package me.pinfort.tsvideos.processing.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.processing.domain.TsVideosException
import java.time.LocalDateTime

class MainSplittedFileFinderComponentTest :
    ExpectSpec({
        val mainSplittedFileFinderComponent = MainSplittedFileFinderComponent()

        fun executedFile(
            drops: Int = 0,
            duration: Double = 100.0,
        ) = ExecutedFile(
            id = 1,
            file = "file",
            drops = drops,
            size = 1,
            recordedAt = LocalDateTime.MIN,
            channel = "channel",
            title = "title",
            channelName = "channelName",
            duration = duration,
            status = ExecutedFile.Status.SPLITTED,
        )

        fun splittedFile(
            id: Long = 1,
            size: Long = 100,
            duration: Double = 100.0,
        ) = SplittedFile(
            id = id,
            executedFileId = 1,
            file = "file$id",
            size = size,
            duration = duration,
            status = SplittedFile.Status.REGISTERED,
        )

        context("find") {
            expect("a single splitted file is the main file") {
                val main = splittedFile()

                mainSplittedFileFinderComponent.find(executedFile(), listOf(main)) shouldBe main
            }

            expect("with two files, the larger one is main") {
                val main = splittedFile(id = 1, size = 1000, duration = 100.0)
                val garbage = splittedFile(id = 2, size = 10, duration = 1.0)

                mainSplittedFileFinderComponent.find(executedFile(), listOf(main, garbage)) shouldBe main
                mainSplittedFileFinderComponent.find(executedFile(), listOf(garbage, main)) shouldBe main
            }

            expect("throws when the garbage file's duration exceeds 20 seconds") {
                val main = splittedFile(id = 1, size = 1000, duration = 100.0)
                val garbage = splittedFile(id = 2, size = 10, duration = 20.1)

                shouldThrow<TsVideosException> {
                    mainSplittedFileFinderComponent.find(executedFile(), listOf(main, garbage))
                }
            }

            expect("does not throw when the garbage file's duration is exactly 20 seconds") {
                val main = splittedFile(id = 1, size = 1000, duration = 100.0)
                val garbage = splittedFile(id = 2, size = 10, duration = 20.0)

                mainSplittedFileFinderComponent.find(executedFile(), listOf(main, garbage)) shouldBe main
            }

            expect("throws when the garbage file's size exceeds 10 percent of the main file's size") {
                val main = splittedFile(id = 1, size = 1000, duration = 1.0)
                val garbage = splittedFile(id = 2, size = 101, duration = 1.0)

                shouldThrow<TsVideosException> {
                    mainSplittedFileFinderComponent.find(executedFile(), listOf(main, garbage))
                }
            }

            expect("throws for any other splitted file count") {
                shouldThrow<TsVideosException> {
                    mainSplittedFileFinderComponent.find(executedFile(), emptyList())
                }
                shouldThrow<TsVideosException> {
                    mainSplittedFileFinderComponent.find(executedFile(), listOf(splittedFile(1), splittedFile(2), splittedFile(3)))
                }
            }

            expect("throws when the main file duration is less than 1 second") {
                val main = splittedFile(duration = 0.5)

                shouldThrow<TsVideosException> {
                    mainSplittedFileFinderComponent.find(executedFile(), listOf(main))
                }
            }

            expect("throws when the executed file has more than 1000 drops") {
                val main = splittedFile(duration = 100.0)

                shouldThrow<TsVideosException> {
                    mainSplittedFileFinderComponent.find(executedFile(drops = 1001), listOf(main))
                }
            }

            expect("does not throw when drops is exactly 1000") {
                val main = splittedFile(duration = 100.0)

                mainSplittedFileFinderComponent.find(executedFile(drops = 1000), listOf(main)) shouldBe main
            }

            expect("throws when main duration is more than 5 seconds longer than the original") {
                val main = splittedFile(duration = 106.0)

                shouldThrow<TsVideosException> {
                    mainSplittedFileFinderComponent.find(executedFile(duration = 100.0), listOf(main))
                }
            }

            expect("does not throw when main duration is exactly 5 seconds longer than the original") {
                val main = splittedFile(duration = 105.0)

                mainSplittedFileFinderComponent.find(executedFile(duration = 100.0), listOf(main)) shouldBe main
            }

            expect("throws when main duration is more than 20 seconds shorter than the original") {
                val main = splittedFile(duration = 79.0)

                shouldThrow<TsVideosException> {
                    mainSplittedFileFinderComponent.find(executedFile(duration = 100.0), listOf(main))
                }
            }

            expect("does not throw when main duration is exactly 20 seconds shorter than the original") {
                val main = splittedFile(duration = 80.0)

                mainSplittedFileFinderComponent.find(executedFile(duration = 100.0), listOf(main)) shouldBe main
            }
        }
    })
