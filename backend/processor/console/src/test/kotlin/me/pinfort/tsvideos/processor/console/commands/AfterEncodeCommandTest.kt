package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.processor.console.parseProcessor
import me.pinfort.tsvideos.processor.infrastructure.pipeline.AfterEncodeRunner
import java.nio.file.Path

class AfterEncodeCommandTest :
    ExpectSpec({
        lateinit var afterEncodeRunner: AfterEncodeRunner
        lateinit var afterEncodeCommand: AfterEncodeCommand

        beforeTest {
            clearAllMocks()
            afterEncodeRunner = mockk()
            afterEncodeCommand = AfterEncodeCommand(afterEncodeRunner)
            every { afterEncodeRunner.run(any(), any(), any()) } just Runs
        }

        fun withEnv(env: Map<String, String>) {
            afterEncodeCommand.context { readEnvvar = { env[it] } }
        }

        context("run") {
            expect("callback failure reaches the application exit boundary unchanged") {
                val failure = java.net.SocketTimeoutException("retry exhausted")
                every { afterEncodeRunner.run(any(), any(), any()) } throws failure
                withEnv(mapOf("ITEM_ID" to "42", "IN_PATH" to "/rec/a.m2ts", "SUCCESS" to "1"))
                shouldThrow<java.net.SocketTimeoutException> {
                    afterEncodeCommand.parseProcessor(emptyList())
                } shouldBe failure
                verify(exactly = 1) { afterEncodeRunner.run(any(), any(), any()) }
            }

            expect("reads every value from the amatsukaze environment variables") {
                withEnv(
                    mapOf(
                        "ITEM_ID" to "42",
                        "IN_PATH" to "/rec/tssplitter/succeeded/a.m2ts",
                        "FILES" to "/rec/tssplitter/encoded/a.mp4;/rec/tssplitter/encoded/b.mp4",
                        "SUCCESS" to "1",
                    ),
                )

                afterEncodeCommand.main(emptyArray())

                verify {
                    afterEncodeRunner.run(
                        AfterEncodeRunner.Input(
                            itemId = 42,
                            inPath = Path.of("/rec/tssplitter/succeeded/a.m2ts"),
                            files =
                                listOf(
                                    Path.of("/rec/tssplitter/encoded/a.mp4"),
                                    Path.of("/rec/tssplitter/encoded/b.mp4"),
                                ),
                            success = true,
                            errorMessage = "",
                        ),
                        false,
                        any(),
                    )
                }
            }

            expect("command line options override the environment") {
                withEnv(mapOf("ITEM_ID" to "42", "IN_PATH" to "/env/a.m2ts", "SUCCESS" to "1"))

                afterEncodeCommand.main(
                    arrayOf("--item-id", "7", "--in-path", "/opt/a.m2ts", "--files", "/opt/a.mp4"),
                )

                verify {
                    afterEncodeRunner.run(
                        match {
                            it.itemId == 7 &&
                                it.inPath == Path.of("/opt/a.m2ts") &&
                                it.files == listOf(Path.of("/opt/a.mp4"))
                        },
                        false,
                        any(),
                    )
                }
            }

            expect("empty FILES becomes an empty list") {
                withEnv(mapOf("ITEM_ID" to "42", "IN_PATH" to "/rec/a.m2ts", "FILES" to "", "SUCCESS" to "1"))

                afterEncodeCommand.main(emptyArray())

                verify { afterEncodeRunner.run(match { it.files.isEmpty() }, false, any()) }
            }

            expect("blank entries in FILES are dropped") {
                withEnv(
                    mapOf(
                        "ITEM_ID" to "42",
                        "IN_PATH" to "/rec/a.m2ts",
                        "FILES" to "/rec/a.mp4; ;/rec/b.mp4;",
                        "SUCCESS" to "1",
                    ),
                )

                afterEncodeCommand.main(emptyArray())

                verify {
                    afterEncodeRunner.run(
                        match { it.files == listOf(Path.of("/rec/a.mp4"), Path.of("/rec/b.mp4")) },
                        false,
                        any(),
                    )
                }
            }

            expect("success is true only for SUCCESS=1") {
                withEnv(
                    mapOf(
                        "ITEM_ID" to "42",
                        "IN_PATH" to "/rec/a.m2ts",
                        "SUCCESS" to "0",
                        "ERROR_MESSAGE" to "encode aborted",
                    ),
                )

                afterEncodeCommand.main(emptyArray())

                verify {
                    afterEncodeRunner.run(
                        match { !it.success && it.errorMessage == "encode aborted" },
                        false,
                        any(),
                    )
                }
            }

            expect("success is false when SUCCESS is unset") {
                withEnv(mapOf("ITEM_ID" to "42", "IN_PATH" to "/rec/a.m2ts"))

                afterEncodeCommand.main(emptyArray())

                verify { afterEncodeRunner.run(match { !it.success }, false, any()) }
            }

            expect("threads the dry-run flag through") {
                withEnv(mapOf("ITEM_ID" to "42", "IN_PATH" to "/rec/a.m2ts", "SUCCESS" to "1"))

                afterEncodeCommand.main(arrayOf("--dry-run"))

                verify { afterEncodeRunner.run(any(), true, any()) }
            }
        }
    })
