package me.pinfort.tsvideos.core.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import me.pinfort.tsvideos.core.catalog.LedgerLocks
import me.pinfort.tsvideos.core.catalog.LedgerSnapshots
import me.pinfort.tsvideos.core.component.DirectoryNameComponent
import me.pinfort.tsvideos.core.component.NormalizeComponent
import me.pinfort.tsvideos.core.domain.BulkAction
import me.pinfort.tsvideos.core.domain.BulkApplyRequest
import me.pinfort.tsvideos.core.domain.BulkOutcome
import me.pinfort.tsvideos.core.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.core.domain.BulkRequest
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.external.database.dto.CreatedFileDto
import me.pinfort.tsvideos.core.external.database.dto.ExecutedFileDto
import me.pinfort.tsvideos.core.external.database.dto.ProgramDto
import me.pinfort.tsvideos.core.external.database.dto.SplittedFileDto
import me.pinfort.tsvideos.core.external.database.mapper.BulkLibraryMapper
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ExecutedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import me.pinfort.tsvideos.core.external.database.mapper.SplittedFileMapper
import org.slf4j.Logger
import org.springframework.dao.CannotAcquireLockException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.LocalDateTime
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

class BulkLibraryCommandTest :
    ExpectSpec({
        // Program lookups are stubbed on the ProgramCommand mock; the catalog's ProgramMapper delegates to it.
        val programs = mockk<ProgramCommand>(relaxed = true)
        val programMapper = mockk<ProgramMapper>()
        val executed = mockk<ExecutedFileMapper>()
        val splits = mockk<SplittedFileMapper>()
        val created = mockk<CreatedFileMapper>()
        val mapper = mockk<BulkLibraryMapper>(relaxed = true)
        val tx = mockk<PlatformTransactionManager>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)

        fun Program.toDto() =
            ProgramDto(
                id,
                name,
                executedFileId,
                ProgramDto.Status.valueOf(status.name),
                drops,
                size,
                recordedAt,
                channel,
                title,
                channelName,
                duration,
            )

        fun command(normalize: NormalizeComponent = NormalizeComponent()) =
            BulkLibraryCommand(
                programs,
                LedgerSnapshots(programMapper, executed, splits, created, mapper),
                LedgerLocks(mapper, programMapper),
                DirectoryNameComponent(NormalizeComponent()),
                normalize,
                tx,
                logger,
            )
        val command = command()
        val program =
            Program(1, "first", 10, Program.Status.COMPLETED, 0, 12, LocalDateTime.of(2026, 1, 1, 0, 0), "1", "title", "channel", 12.0)
        val source =
            ExecutedFileDto(
                10,
                "C:\\original.m2ts",
                0,
                12,
                program.recordedAt,
                "1",
                "title",
                "channel",
                12.0,
                ExecutedFileDto.Status.SPLITTED,
            )
        val split = SplittedFileDto(20, 10, "C:\\split.m2ts", 12, 12.0, SplittedFileDto.Status.ENCODE_TASK_ADDED)
        val file = CreatedFileDto(30, 20, "base/a/alpha/video.mp4", 12, "video/mp4", null, CreatedFileDto.Status.FILE_MOVED, 1920, 1080)
        val request = BulkRequest(listOf(1), BulkAction.DELETE)
        beforeTest {
            clearMocks(programs, programMapper, executed, splits, created, mapper, tx, logger)
            every { tx.getTransaction(any()) } returns SimpleTransactionStatus()
            every { programMapper.find(any()) } answers { programs.find(firstArg())?.toDto() }
            every { programs.find(any()) } returns program
            every { executed.find(10) } returns source
            every { splits.selectByExecutedFileId(10) } returns listOf(split)
            every { created.selectByExecutedFileId(10) } returns listOf(file)
            every { mapper.references(10) } returns listOf(1)
            every { mapper.allFiles() } returns listOf(file)
            every { mapper.lockFiles() } answers { mapper.allFiles() }
            every { mapper.createdFilesOfPrograms(any()) } answers {
                firstArg<List<Long>>().filter { programs.find(it) != null }.flatMap { created.selectByExecutedFileId(10) }
            }
            every { mapper.lockProgram(any()) } answers { firstArg() }
        }
        expect("preview is read only and includes all records and original preservation") {
            val plan = command.preview(request)
            plan.targets.single().eligible shouldBe true
            plan.targets
                .single()
                .snapshot
                .shouldNotBeNull()
                .executed shouldBe source.toDomain()
            plan.targets.single().estimatedMetadataBytes shouldBe 12
            plan.originalSourcePreserved shouldBe true
            verify(exactly = 0) {
                programs.delete(any(), any())
                programs.moveCreatedFiles(any(), any(), any())
                mapper.lockFiles()
            }
        }
        expect("added removed or modified files and program status make the plan stale") {
            val plan = command.preview(request)
            for (files in listOf(
                emptyList(),
                listOf(file, file.copy(id = 31, file = "base/a/alpha/new.mp4")),
                listOf(file.copy(size = 99)),
                listOf(file.copy(file = "base/b/beta/video.mp4")),
                listOf(file.copy(status = CreatedFileDto.Status.REGISTERED)),
            )) {
                every { created.selectByExecutedFileId(10) } returns files
                command
                    .apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint)))
                    .outcomes
                    .single()
                    .status shouldBe
                    BulkOutcomeStatus.STALE
            }
            every { created.selectByExecutedFileId(10) } returns listOf(file)
            every { programs.find(any()) } returns program.copy(status = Program.Status.ERROR)
            command
                .apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint)))
                .outcomes
                .single()
                .status shouldBe
                BulkOutcomeStatus.STALE
            verify(exactly = 0) { programs.delete(any(), any()) }
        }
        expect("every snapshot field is covered by the fingerprint") {
            // Fails when a DTO gains a field that BulkLibraryCommand.fingerprint does not encode.
            fun <T : Any> variants(value: T): List<Pair<String, T>> {
                val constructor = value::class.primaryConstructor.shouldNotBeNull()
                return constructor.parameters.map { parameter ->
                    val current =
                        value::class
                            .memberProperties
                            .single { it.name == parameter.name }
                            .getter
                            .call(value)
                    val changed: Any =
                        when (current) {
                            is Long -> current + 1000
                            is Int -> current + 1
                            is Double -> current + 0.5
                            is String -> current + "x"
                            is LocalDateTime -> current.plusDays(1)
                            is Enum<*> -> current.javaClass.enumConstants.first { it != current }
                            null -> "x"
                            else -> error("Add a variant for ${parameter.name}: ${current::class}")
                        }
                    val arguments =
                        constructor.parameters.associateWith { p ->
                            value::class
                                .memberProperties
                                .single { it.name == p.name }
                                .getter
                                .call(value)
                        }
                    @Suppress("UNCHECKED_CAST")
                    parameter.name.shouldNotBeNull() to constructor.callBy(arguments + (parameter to changed)) as T
                }
            }
            val baseline =
                command
                    .preview(request)
                    .targets
                    .single()
                    .fingerprint

            fun fingerprintWith(setup: () -> Unit): String {
                setup()
                return command.preview(request).targets.single().fingerprint.also {
                    every { programs.find(any()) } returns program
                    every { executed.find(any()) } returns source
                    every { splits.selectByExecutedFileId(any()) } returns listOf(split)
                    every { created.selectByExecutedFileId(any()) } returns listOf(file)
                    every { mapper.references(any()) } returns listOf(1)
                }
            }
            every { executed.find(any()) } returns source
            every { splits.selectByExecutedFileId(any()) } returns listOf(split)
            every { created.selectByExecutedFileId(any()) } returns listOf(file)
            every { mapper.references(any()) } returns listOf(1)
            for ((name, changed) in variants(program)) {
                withClue("Program.$name") { fingerprintWith { every { programs.find(any()) } returns changed } shouldNotBe baseline }
            }
            for ((name, changed) in variants(source)) {
                withClue(
                    "ExecutedFileDto.$name",
                ) { fingerprintWith { every { executed.find(any()) } returns changed } shouldNotBe baseline }
            }
            for ((name, changed) in variants(split)) {
                withClue("SplittedFileDto.$name") {
                    fingerprintWith { every { splits.selectByExecutedFileId(any()) } returns listOf(changed) } shouldNotBe baseline
                }
            }
            for ((name, changed) in variants(file)) {
                withClue("CreatedFileDto.$name") {
                    fingerprintWith { every { created.selectByExecutedFileId(any()) } returns listOf(changed) } shouldNotBe baseline
                }
            }
        }
        expect("executed and split changes invalidate snapshots") {
            val plan = command.preview(request)
            every { executed.find(10) } returns source.copy(file = "changed")
            command
                .apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint)))
                .outcomes
                .single()
                .status shouldBe
                BulkOutcomeStatus.STALE
            every { executed.find(10) } returns source
            every { splits.selectByExecutedFileId(10) } returns listOf(split.copy(status = SplittedFileDto.Status.REGISTERED))
            command
                .apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint)))
                .outcomes
                .single()
                .status shouldBe
                BulkOutcomeStatus.STALE
        }
        expect("shared normalized paths and shared executed records are refused and excluded from estimates") {
            every { mapper.allFiles() } returns listOf(file, file.copy(id = 99, file = "BASE\\A\\ALPHA\\VIDEO.MP4"))
            command
                .preview(request)
                .targets
                .single()
                .eligible shouldBe false
            command
                .preview(request)
                .targets
                .single()
                .estimatedMetadataBytes shouldBe 0
            every { mapper.allFiles() } returns listOf(file)
            every { mapper.references(10) } returns listOf(1, 2)
            command
                .preview(request)
                .targets
                .single()
                .eligible shouldBe false
        }
        expect("move normalizes directory, shows exact path, rejects collisions and same destination") {
            val move = BulkRequest(listOf(1), BulkAction.MOVE, "Ｂeta")
            command
                .preview(move)
                .targets
                .single()
                .destinations shouldBe mapOf(30L to "base\\B\\Beta\\video.mp4")
            command
                .preview(move)
                .targets
                .single()
                .estimatedMetadataBytes shouldBe 12
            command
                .preview(BulkRequest(listOf(1), BulkAction.MOVE, "alpha"))
                .targets
                .single()
                .eligible shouldBe false
            every { mapper.allFiles() } returns listOf(file, file.copy(id = 99, file = "base/B/Beta/video.mp4"))
            command
                .preview(move)
                .targets
                .single()
                .eligible shouldBe false
        }
        expect("unsafe source path and invalid directories never execute") {
            for (directory in listOf("", " ", ".", "..", "../x", "a\\b", "a\nb", "x.", "%2e%2e", "name#fragment")) {
                shouldThrow<IllegalArgumentException> { command.preview(BulkRequest(listOf(1), BulkAction.MOVE, directory)) }
            }
            every { created.selectByExecutedFileId(10) } returns listOf(file.copy(file = "../x"))
            command
                .preview(BulkRequest(listOf(1), BulkAction.MOVE, "valid"))
                .targets
                .single()
                .eligible shouldBe false
        }
        expect("URL interpretation and trailing aliases cannot escape shared-path protection") {
            for (path in listOf(
                "base/a/alpha/%2e%2e%2fother",
                "base/a/alpha/file#fragment",
                "base/a/alpha/file?query",
                "base/a/alpha/file.",
            )) {
                every { created.selectByExecutedFileId(10) } returns listOf(file.copy(file = path))
                command
                    .preview(request)
                    .targets
                    .single()
                    .eligible shouldBe false
            }
            every { created.selectByExecutedFileId(10) } returns listOf(file)
            every { mapper.allFiles() } returns listOf(file, file.copy(id = 99, file = file.file + "."))
            command
                .preview(request)
                .targets
                .single()
                .eligible shouldBe false
        }
        expect("only completed recordings and available files are eligible") {
            every { programs.find(any()) } returns program.copy(status = Program.Status.ERROR)
            command
                .preview(request)
                .targets
                .single()
                .eligible shouldBe false
            every { programs.find(any()) } returns program
            every { created.selectByExecutedFileId(10) } returns listOf(file.copy(status = CreatedFileDto.Status.ENCODE_SUCCESS))
            command
                .preview(request)
                .targets
                .single()
                .eligible shouldBe false
        }
        expect("partial failure is returned per target and subsequent targets run") {
            val batch = BulkRequest(listOf(1, 2), BulkAction.DELETE)
            every { programs.find(2) } returns program.copy(id = 2)
            every { mapper.references(10) } returns emptyList()
            every { programs.delete(program, false) } throws IllegalStateException("credential secret")
            val plan = command.preview(batch)
            val result = command.apply(BulkApplyRequest(batch, plan.targets.associate { it.id to it.fingerprint }))
            result.outcomes.map { it.status } shouldBe listOf(BulkOutcomeStatus.FAILED, BulkOutcomeStatus.SUCCESS)
            result.outcomes
                .first()
                .message
                .contains("secret") shouldBe false
            verify(exactly = 1) { programs.delete(program.copy(id = 2), false) }
            verify(exactly = 1) { logger.error(match { it.contains("programId=1") }, any<IllegalStateException>()) }
        }
        expect("a program that disappears between lock and lookup is NOT_FOUND rather than FAILED") {
            val plan = command.preview(request)
            every { programs.find(1) } returns null
            command
                .apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint)))
                .outcomes
                .single()
                .status shouldBe
                BulkOutcomeStatus.NOT_FOUND
        }
        expect("directory labels are re-checked after normalization") {
            val normalize = mockk<NormalizeComponent>()
            for (normalized in listOf("a/b", "a\\b", "a#b", "a%b", "a:b", "x.", ".")) {
                every { normalize.normalize(any<String>()) } returns normalized
                shouldThrow<IllegalArgumentException> {
                    command(normalize).preview(BulkRequest(listOf(1), BulkAction.MOVE, "valid"))
                }
            }
        }
        expect("move without generated files is refused instead of reporting a no-op success") {
            every { created.selectByExecutedFileId(10) } returns emptyList()
            command
                .preview(BulkRequest(listOf(1), BulkAction.MOVE, "beta"))
                .targets
                .single()
                .reason shouldBe "No generated files to move"
        }
        expect("earlier committed moves in the same apply do not make later targets stale") {
            val second = program.copy(id = 2, name = "second", executedFileId = 11)
            val secondFile = file.copy(id = 31, splittedFileId = 21, file = "base/s/second/video2.mp4")
            val files = mutableListOf(file, secondFile)
            every { programs.find(2) } returns second
            every { executed.find(11) } returns source.copy(id = 11)
            every { splits.selectByExecutedFileId(11) } returns listOf(split.copy(id = 21, executedFileId = 11))
            every { created.selectByExecutedFileId(10) } answers { listOf(files[0]) }
            every { created.selectByExecutedFileId(11) } answers { listOf(files[1]) }
            every { mapper.references(11) } returns listOf(2)
            every { mapper.allFiles() } answers { files.toList() }
            every { mapper.createdFilesOfPrograms(any()) } answers { files.toList() }
            every { programs.moveCreatedFiles(program, "beta", any()) } answers {
                files[0] = file.copy(file = "base/B/beta/video.mp4")
            }
            val move = BulkRequest(listOf(1, 2), BulkAction.MOVE, "beta")
            val plan = command.preview(move)
            command
                .apply(BulkApplyRequest(move, plan.targets.associate { it.id to it.fingerprint }))
                .outcomes
                .map { it.status } shouldBe listOf(BulkOutcomeStatus.SUCCESS, BulkOutcomeStatus.SUCCESS)
        }
        expect("a 100-target move preview issues a linear number of queries") {
            val ids = (1L..100L).toList()
            every { programs.find(any()) } answers { program.copy(id = firstArg()) }
            every { mapper.createdFilesOfPrograms(ids) } returns ids.map { file }
            command.preview(BulkRequest(ids, BulkAction.MOVE, "beta")).targets.size shouldBe 100
            verify(exactly = 1) {
                mapper.allFiles()
                mapper.createdFilesOfPrograms(ids)
            }
            verify(exactly = 100) { created.selectByExecutedFileId(10) }
        }
        expect("a lock wait timeout reports a busy library without mutating anything") {
            val plan = command.preview(request)
            every { mapper.lockFiles() } throws CannotAcquireLockException("Lock wait timeout exceeded")
            val outcome = command.apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint))).outcomes.single()
            outcome.status shouldBe BulkOutcomeStatus.FAILED
            outcome.message.contains("nothing was changed") shouldBe true
            verify(exactly = 0) { programs.delete(any(), any()) }
        }
        expect("a lock failure after mutation started is not reported as harmless") {
            val plan = command.preview(request)
            every { programs.delete(program, any()) } throws CannotAcquireLockException("Lock wait timeout exceeded")
            val outcome = command.apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint))).outcomes.single()
            outcome.status shouldBe BulkOutcomeStatus.FAILED
            outcome.message.contains("nothing was changed") shouldBe false
            outcome.message.contains("partially") shouldBe true
        }
        expect("a non-lock failure before mutation says nothing was changed") {
            val plan = command.preview(request)
            every { mapper.lockExecuted(any()) } throws IllegalStateException("db down")
            val outcome = command.apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint))).outcomes.single()
            outcome.status shouldBe BulkOutcomeStatus.FAILED
            outcome.message.contains("nothing was changed") shouldBe true
            verify(exactly = 0) { programs.delete(any(), any()) }
        }
        expect("an interrupt stops later targets from starting") {
            val batch = BulkRequest(listOf(1, 2), BulkAction.DELETE)
            every { programs.find(2) } returns program.copy(id = 2)
            every { mapper.references(10) } returns emptyList()
            val plan = command.preview(batch)
            every { programs.delete(program, false) } throws InterruptedException("shutdown")
            try {
                val result = command.apply(BulkApplyRequest(batch, plan.targets.associate { it.id to it.fingerprint }))
                result.outcomes.map { it.status } shouldBe listOf(BulkOutcomeStatus.FAILED, BulkOutcomeStatus.FAILED)
                result.outcomes[1].message.contains("Interrupted") shouldBe true
                verify(exactly = 0) { programs.delete(program.copy(id = 2), any()) }
            } finally {
                Thread.interrupted()
            }
        }
        expect("apply takes the ledger locks in the documented order before re-reading and mutating") {
            val plan = command.preview(request)
            val result = command.apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint)))
            result.outcomes.single().status shouldBe BulkOutcomeStatus.SUCCESS
            verifyOrder {
                mapper.lockFiles()
                mapper.lockProgram(1)
                programMapper.find(1)
                mapper.lockExecuted(10)
                mapper.lockSplits(10)
                mapper.lockReferences(10)
                programMapper.find(1)
                executed.find(10)
                programs.delete(program, false)
            }
            verify(exactly = 0) {
                mapper.lockAllPrograms()
                mapper.lockAllExecuted()
                mapper.lockAllSplits()
            }
        }
        expect("guarded apply locks the whole ledger after the created files and before the target") {
            val plan = command.preview(request)
            val guarded = mutableListOf<Long>()
            val result =
                command.applyGuarded(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint))) { id, _ ->
                    guarded += id
                    null
                }
            result.outcomes.single().status shouldBe BulkOutcomeStatus.SUCCESS
            guarded shouldBe listOf(1L)
            verifyOrder {
                mapper.lockFiles()
                mapper.lockAllPrograms()
                mapper.lockAllExecuted()
                mapper.lockAllSplits()
                mapper.lockProgram(1)
                mapper.lockExecuted(10)
                mapper.lockSplits(10)
                mapper.lockReferences(10)
                programs.delete(program, false)
            }
        }
        expect("a guard veto returns before the target is locked or changed") {
            val plan = command.preview(request)
            val result =
                command.applyGuarded(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint))) { id, _ ->
                    BulkOutcome(id, BulkOutcomeStatus.STALE, "changed")
                }
            result.outcomes.single().status shouldBe BulkOutcomeStatus.STALE
            verify(exactly = 0) {
                mapper.lockProgram(any())
                programs.delete(any(), any())
            }
        }
        expect("preview snapshots publish the stored row properties only") {
            val snapshot =
                command
                    .preview(request)
                    .targets
                    .single()
                    .snapshot
                    .shouldNotBeNull()

            fun names(type: KClass<*>) = type.memberProperties.map { it.name }.toSet()
            names(snapshot.executed.shouldNotBeNull()::class) shouldBe names(ExecutedFileDto::class)
            names(snapshot.splitFiles.single()::class) shouldBe names(SplittedFileDto::class)
            names(snapshot.createdFiles.single()::class) shouldBe names(CreatedFileDto::class)
            snapshot.createdFiles
                .single()
                .status.name shouldBe file.status.name
        }
        expect("missing target is a per-target result") {
            val plan = command.preview(request)
            every { mapper.lockProgram(1) } returns null
            command
                .apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint)))
                .outcomes
                .single()
                .status shouldBe
                BulkOutcomeStatus.NOT_FOUND
        }
        expect("invalid batch sizes duplicate IDs and missing fingerprints are rejected") {
            for (ids in listOf(emptyList(), listOf(0L), listOf(1L, 1L), (1L..101L).toList())) {
                shouldThrow<IllegalArgumentException> {
                    command.preview(request.copy(ids = ids))
                }
            }
            shouldThrow<IllegalArgumentException> { command.apply(BulkApplyRequest(request, emptyMap())) }
        }
    })
