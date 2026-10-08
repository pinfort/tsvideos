package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import me.pinfort.tsvideos.core.command.CreatedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.component.MimeTypeComponent
import me.pinfort.tsvideos.core.component.NasDestinationResolver
import me.pinfort.tsvideos.core.component.NormalizeComponent
import me.pinfort.tsvideos.core.component.ValidateCompletedComponent
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.ExecutedFile
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.SplittedFile
import me.pinfort.tsvideos.core.external.samba.NasComponent
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.processor.infrastructure.external.slack.SlackClient
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

/**
 * <root>/myprogram/rec.m2ts                            録画元ファイル (executed_file)
 * <root>/myprogram/tssplitter/rec_1.m2ts               分割済みファイル (splitted_file)
 * <root>/myprogram/tssplitter/succeeded/rec_1.m2ts     Amatsukazeが移動した入力ファイル (IN_PATH)
 * <root>/myprogram/tssplitter/encoded/rec_1.mp4        エンコード済みファイル (FILES)
 */
private class Fixture {
    val root: File = Files.createTempDirectory("after-encode-test").toFile()
    val recordingDir = File(root, "myprogram").also { it.mkdirs() }
    val tssplitterDir = File(recordingDir, "tssplitter").also { it.mkdirs() }
    val succeededDir = File(tssplitterDir, "succeeded").also { it.mkdirs() }
    val encodedDir = File(tssplitterDir, "encoded").also { it.mkdirs() }
    val executedFile = File(recordingDir, "rec.m2ts").also { it.writeBytes("executed".toByteArray()) }
    val originalSplitFile = File(tssplitterDir, "rec_1.m2ts").also { it.writeBytes("splitted".toByteArray()) }
    val inFile = File(succeededDir, "rec_1.m2ts").also { it.writeBytes("splitted".toByteArray()) }
    val outFile = File(encodedDir, "rec_1.mp4").also { it.writeBytes("encoded".toByteArray()) }
}

class AfterEncodeRunnerTest :
    ExpectSpec({
        lateinit var splittedFileCommand: SplittedFileCommand
        lateinit var createdFileCommand: CreatedFileCommand
        lateinit var executedFileCommand: ExecutedFileCommand
        lateinit var programCommand: ProgramCommand
        lateinit var nasComponent: NasComponent
        lateinit var sambaClient: SambaClient
        lateinit var validateCompletedComponent: ValidateCompletedComponent
        lateinit var slackClient: SlackClient
        lateinit var logger: Logger
        lateinit var afterEncodeRunner: AfterEncodeRunner

        val mimeTypeComponent = MimeTypeComponent()

        fun splittedFileOf(fixture: Fixture) =
            SplittedFile(
                id = 20,
                executedFileId = 10,
                file = fixture.originalSplitFile.absolutePath,
                size = 8,
                duration = 100.0,
                status = SplittedFile.Status.COMPRESS_SAVED,
            )

        fun executedFileOf(fixture: Fixture) =
            ExecutedFile(
                id = 10,
                file = fixture.executedFile.absolutePath,
                drops = 0,
                size = 8,
                recordedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 100.0,
                status = ExecutedFile.Status.SPLITTED,
            )

        val program =
            Program(
                id = 1,
                name = "rec.m2ts",
                executedFileId = 10,
                status = Program.Status.REGISTERED,
                drops = 0,
                size = 8,
                recordedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
                channel = "channel",
                title = "title",
                channelName = "channelName",
                duration = 100.0,
            )

        fun inputOf(
            fixture: Fixture,
            success: Boolean = true,
            errorMessage: String = "",
        ) = AfterEncodeRunner.Input(
            itemId = 1,
            inPath = fixture.inFile.toPath(),
            files = listOf(fixture.outFile.toPath()),
            success = success,
            errorMessage = errorMessage,
        )

        /** registerFiles / moveFiles が成功する状態にする */
        fun stubRegisterAndMove(fixture: Fixture) {
            every { splittedFileCommand.findByFile(fixture.originalSplitFile.absolutePath) } returns splittedFileOf(fixture)
            every {
                sambaClient.resolvePathUnderBaseDir(SambaClient.NasType.VIDEO_STORE_NAS, any())
            } answers { "nas-base-dir/${secondArg<String>()}" }
            every {
                createdFileCommand.insert(any(), any(), any(), any(), any(), any(), any())
            } answers {
                CreatedFile(
                    id = 300,
                    splittedFileId = firstArg(),
                    file = secondArg(),
                    size = thirdArg(),
                    mime = arg(3),
                    encoding = arg(4),
                    status = arg(5),
                )
            }
            every { nasComponent.uploadResource(any(), any(), any(), any()) } returns true
            every { createdFileCommand.updateStatus(any(), any(), any()) } answers { firstArg() }
        }

        beforeTest {
            clearAllMocks()
            splittedFileCommand = mockk()
            createdFileCommand = mockk()
            executedFileCommand = mockk()
            programCommand = mockk()
            nasComponent = mockk()
            sambaClient = mockk()
            validateCompletedComponent = mockk()
            slackClient = mockk()
            logger = mockk(relaxed = true)
            afterEncodeRunner =
                AfterEncodeRunner(
                    splittedFileCommand,
                    createdFileCommand,
                    executedFileCommand,
                    programCommand,
                    nasComponent,
                    NasDestinationResolver(NormalizeComponent(), sambaClient),
                    mimeTypeComponent,
                    validateCompletedComponent,
                    slackClient,
                    logger,
                )
        }

        context("run - encode failed") {
            expect("notifies slack and touches nothing") {
                val fixture = Fixture()
                every { slackClient.notify(any()) } just Runs

                afterEncodeRunner.run(inputOf(fixture, success = false, errorMessage = "out of memory"))

                verify { slackClient.notify(match { it.contains("encode failed") && it.contains("out of memory") }) }
                verify(exactly = 0) { splittedFileCommand.findByFile(any()) }
                verify(exactly = 0) { createdFileCommand.insert(any(), any(), any(), any(), any(), any(), any()) }
                fixture.outFile.exists() shouldBe true
                fixture.inFile.exists() shouldBe true
            }
        }

        context("run - original splitted file not found") {
            expect("aborts before registering anything") {
                val fixture = Fixture()
                every { splittedFileCommand.findByFile(any()) } returns null
                every { slackClient.notify(any()) } just Runs

                afterEncodeRunner.run(inputOf(fixture))

                verify { slackClient.notify(match { it.contains("original splitted file not found") }) }
                verify(exactly = 0) { createdFileCommand.insert(any(), any(), any(), any(), any(), any(), any()) }
                verify(exactly = 0) { nasComponent.uploadResource(any(), any(), any(), any()) }
                fixture.outFile.exists() shouldBe true
            }
        }

        context("run - success") {
            expect("registers, uploads to the video store nas and completes the program") {
                val fixture = Fixture()
                stubRegisterAndMove(fixture)
                every { programCommand.findByExecutedFileId(10) } returns program
                every { validateCompletedComponent.validate(1) } returns true
                every { executedFileCommand.find(10) } returns executedFileOf(fixture)
                every { splittedFileCommand.selectByExecutedFileId(10) } returns listOf(splittedFileOf(fixture))
                every { programCommand.updateStatusByExecutedFileId(any(), any(), any()) } just Runs

                afterEncodeRunner.run(inputOf(fixture))

                // 録画ディレクトリ名から バケット/番組ディレクトリ を組み立て、baseDir込みのパスで登録・アップロードする
                verify {
                    createdFileCommand.insert(
                        20,
                        "nas-base-dir/m/myprogram/rec_1.mp4",
                        7,
                        "video/mp4",
                        null,
                        CreatedFile.Status.ENCODE_SUCCESS,
                        false,
                    )
                }
                verifyOrder {
                    nasComponent.uploadResource(
                        fixture.outFile,
                        "nas-base-dir/m/myprogram/rec_1.mp4",
                        SambaClient.NasType.VIDEO_STORE_NAS,
                        any(),
                    )
                    createdFileCommand.updateStatus(any(), CreatedFile.Status.FILE_MOVED, false)
                }
                verify { programCommand.updateStatusByExecutedFileId(10, Program.Status.COMPLETED, false) }
                verify(exactly = 0) { slackClient.notify(any()) }

                // ローカルの中間ファイルはすべて削除される
                fixture.outFile.exists() shouldBe false
                fixture.inFile.exists() shouldBe false
                fixture.executedFile.exists() shouldBe false
                fixture.originalSplitFile.exists() shouldBe false
            }

            expect("marks the program as ERROR when the validation fails") {
                val fixture = Fixture()
                stubRegisterAndMove(fixture)
                every { programCommand.findByExecutedFileId(10) } returns program
                every { validateCompletedComponent.validate(1) } returns false
                every { programCommand.updateStatusByExecutedFileId(any(), any(), any()) } just Runs
                every { slackClient.notify(any()) } just Runs

                afterEncodeRunner.run(inputOf(fixture))

                verify { slackClient.notify(match { it.contains("program invalid") }) }
                verify { programCommand.updateStatusByExecutedFileId(10, Program.Status.ERROR, false) }
                // 検証に失敗したので元ファイルは残す
                fixture.inFile.exists() shouldBe true
                fixture.executedFile.exists() shouldBe true
                verify(exactly = 0) { executedFileCommand.find(any()) }
            }

            expect("marks the program as ERROR when the completion process throws") {
                val fixture = Fixture()
                stubRegisterAndMove(fixture)
                every { programCommand.findByExecutedFileId(10) } returns program
                every { validateCompletedComponent.validate(1) } throws RuntimeException("nas is down")
                every { programCommand.updateStatusByExecutedFileId(any(), any(), any()) } just Runs
                every { slackClient.notify(any()) } just Runs

                afterEncodeRunner.run(inputOf(fixture))

                verify { slackClient.notify(match { it.contains("nas is down") }) }
                verify { programCommand.updateStatusByExecutedFileId(10, Program.Status.ERROR, false) }
            }

            expect("notifies and stops after the upload when the program is not found") {
                val fixture = Fixture()
                stubRegisterAndMove(fixture)
                every { programCommand.findByExecutedFileId(10) } returns null
                every { slackClient.notify(any()) } just Runs

                afterEncodeRunner.run(inputOf(fixture))

                verify { nasComponent.uploadResource(any(), any(), any(), any()) }
                verify { slackClient.notify(match { it.contains("program not found") }) }
                verify(exactly = 0) { programCommand.updateStatusByExecutedFileId(any(), any(), any()) }
                fixture.inFile.exists() shouldBe true
            }

            expect("marks the program as ERROR when the nas upload throws") {
                val fixture = Fixture()
                stubRegisterAndMove(fixture)
                every {
                    nasComponent.uploadResource(any(), any(), any(), any())
                } throws RuntimeException("smb timeout")
                every { programCommand.updateStatusByExecutedFileId(any(), any(), any()) } just Runs
                every { slackClient.notify(any()) } just Runs

                // 無人のバッチから呼ばれるため、例外を投げずに通知して終わる
                afterEncodeRunner.run(inputOf(fixture))

                verify { slackClient.notify(match { it.contains("uploading encoded files failed") && it.contains("smb timeout") }) }
                verify { programCommand.updateStatusByExecutedFileId(10, Program.Status.ERROR, false) }
                verify(exactly = 0) { validateCompletedComponent.validate(any()) }
                // 完了処理まで進まないので元ファイルは残る
                fixture.inFile.exists() shouldBe true
                fixture.executedFile.exists() shouldBe true
            }

            expect("marks the program as ERROR when registering a created file throws") {
                val fixture = Fixture()
                stubRegisterAndMove(fixture)
                every {
                    createdFileCommand.insert(any(), any(), any(), any(), any(), any(), any())
                } throws RuntimeException("duplicate entry")
                every { programCommand.updateStatusByExecutedFileId(any(), any(), any()) } just Runs
                every { slackClient.notify(any()) } just Runs

                afterEncodeRunner.run(inputOf(fixture))

                verify { slackClient.notify(match { it.contains("duplicate entry") }) }
                verify { programCommand.updateStatusByExecutedFileId(10, Program.Status.ERROR, false) }
                verify(exactly = 0) { nasComponent.uploadResource(any(), any(), any(), any()) }
                fixture.outFile.exists() shouldBe true
            }
        }

        context("run - dryRun") {
            expect("writes nothing to the nas and keeps every local file") {
                val fixture = Fixture()
                stubRegisterAndMove(fixture)
                every { programCommand.findByExecutedFileId(10) } returns program
                every { validateCompletedComponent.validate(1) } returns true
                every { executedFileCommand.find(10) } returns executedFileOf(fixture)
                every { splittedFileCommand.selectByExecutedFileId(10) } returns listOf(splittedFileOf(fixture))
                every { programCommand.updateStatusByExecutedFileId(any(), any(), any()) } just Runs

                afterEncodeRunner.run(inputOf(fixture), dryRun = true)

                verify(exactly = 0) { nasComponent.uploadResource(any(), any(), any(), any()) }
                verify {
                    createdFileCommand.insert(any(), any(), any(), any(), any(), CreatedFile.Status.ENCODE_SUCCESS, true)
                }
                verify { createdFileCommand.updateStatus(any(), CreatedFile.Status.FILE_MOVED, true) }
                verify { programCommand.updateStatusByExecutedFileId(10, Program.Status.COMPLETED, true) }
                fixture.outFile.exists() shouldBe true
                fixture.inFile.exists() shouldBe true
                fixture.executedFile.exists() shouldBe true
                fixture.originalSplitFile.exists() shouldBe true
            }
        }
    })
