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
import me.pinfort.tsvideos.catalog.api.CreatedFileSnapshot
import me.pinfort.tsvideos.catalog.api.DirectoryNameComponent
import me.pinfort.tsvideos.catalog.api.LedgerLocks
import me.pinfort.tsvideos.catalog.api.LedgerSnapshots
import me.pinfort.tsvideos.catalog.api.NormalizeComponent
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.api.ProgramSnapshot
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.ExecutedFile
import me.pinfort.tsvideos.catalog.domain.Program
import me.pinfort.tsvideos.catalog.domain.SplittedFile
import me.pinfort.tsvideos.core.domain.BulkAction
import me.pinfort.tsvideos.core.domain.BulkApplyRequest
import me.pinfort.tsvideos.core.domain.BulkOutcome
import me.pinfort.tsvideos.core.domain.BulkOutcomeStatus
import me.pinfort.tsvideos.core.domain.BulkRequest
import org.slf4j.Logger
import org.springframework.dao.CannotAcquireLockException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.LocalDateTime
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * The ledger rows the catalog reads for one recording, stubbed per test. The catalog operations are mocked over them
 * the way catalog assembles them (catalog's LedgerSnapshotsTest and LedgerLocksTest cover that assembly and the lock
 * order inside [LedgerLocks.lockProgramGraph]).
 */
internal interface RecordingRows {
    fun executed(executedFileId: Long): ExecutedFile?

    fun splits(executedFileId: Long): List<SplittedFile>

    fun created(executedFileId: Long): List<CreatedFileSnapshot>
}

class BulkLibraryCommandTest :
    ExpectSpec({
        val programs = mockk<ProgramCommand>(relaxed = true)
        val rows = mockk<RecordingRows>()
        val snapshots = mockk<LedgerSnapshots>()
        val locks = mockk<LedgerLocks>(relaxed = true)
        val tx = mockk<PlatformTransactionManager>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)

        fun command(normalize: NormalizeComponent = NormalizeComponent()) =
            BulkLibraryCommand(
                programs,
                snapshots,
                locks,
                DirectoryNameComponent(NormalizeComponent()),
                normalize,
                tx,
                logger,
            )
        val command = command()
        val program =
            Program(1, "first", 10, Program.Status.COMPLETED, 0, 12, LocalDateTime.of(2026, 1, 1, 0, 0), "1", "title", "channel", 12.0)
        val source =
            ExecutedFile(
                10,
                "C:\\original.m2ts",
                0,
                12,
                program.recordedAt,
                "1",
                "title",
                "channel",
                12.0,
                ExecutedFile.Status.SPLITTED,
            )
        val split = SplittedFile(20, 10, "C:\\split.m2ts", 12, 12.0, SplittedFile.Status.ENCODE_TASK_ADDED)
        val file = CreatedFileSnapshot(30, 20, "base/a/alpha/video.mp4", 12, "video/mp4", null, CreatedFile.Status.FILE_MOVED, 1920, 1080)
        val request = BulkRequest(listOf(1), BulkAction.DELETE)
        beforeTest {
            clearMocks(programs, rows, snapshots, locks, tx, logger)
            every { tx.getTransaction(any()) } returns SimpleTransactionStatus()
            every { programs.find(any()) } returns program
            every { rows.executed(10) } returns source
            every { rows.splits(10) } returns listOf(split)
            every { rows.created(10) } returns listOf(file)
            every { snapshots.programSnapshot(any()) } answers {
                programs.find(firstArg())?.let {
                    ProgramSnapshot(it, rows.executed(it.executedFileId), rows.splits(it.executedFileId), rows.created(it.executedFileId))
                }
            }
            every { snapshots.referencingPrograms(10) } returns listOf(1)
            every { snapshots.allCreatedFiles() } returns listOf(file)
            every { locks.lockCreatedFiles() } answers { snapshots.allCreatedFiles() }
            every { snapshots.createdFilesOfPrograms(any()) } answers {
                firstArg<List<Long>>().filter { programs.find(it) != null }.flatMap { rows.created(10) }
            }
            every { locks.lockProgramGraph(any()) } answers { programs.find(firstArg()) }
        }
        expect("preview is read only and includes all records and original preservation") {
            val plan = command.preview(request)
            plan.targets.single().eligible shouldBe true
            plan.targets
                .single()
                .snapshot
                .shouldNotBeNull()
                .executed shouldBe source
            plan.targets.single().estimatedMetadataBytes shouldBe 12
            plan.originalSourcePreserved shouldBe true
            verify(exactly = 0) {
                programs.delete(any(), any())
                programs.moveCreatedFiles(any(), any(), any())
                locks.lockCreatedFiles()
            }
        }
        expect("added removed or modified files and program status make the plan stale") {
            val plan = command.preview(request)
            for (files in listOf(
                emptyList(),
                listOf(file, file.copy(id = 31, file = "base/a/alpha/new.mp4")),
                listOf(file.copy(size = 99)),
                listOf(file.copy(file = "base/b/beta/video.mp4")),
                listOf(file.copy(status = CreatedFile.Status.REGISTERED)),
            )) {
                every { rows.created(10) } returns files
                command
                    .apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint)))
                    .outcomes
                    .single()
                    .status shouldBe
                    BulkOutcomeStatus.STALE
            }
            every { rows.created(10) } returns listOf(file)
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
            // Fails when a snapshot type gains a field that BulkLibraryCommand.fingerprint does not encode. The snapshot
            // types carry exactly the stored columns (catalog's LedgerSnapshotsTest).
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
                    every { rows.executed(any()) } returns source
                    every { rows.splits(any()) } returns listOf(split)
                    every { rows.created(any()) } returns listOf(file)
                    every { snapshots.referencingPrograms(any()) } returns listOf(1)
                }
            }
            every { rows.executed(any()) } returns source
            every { rows.splits(any()) } returns listOf(split)
            every { rows.created(any()) } returns listOf(file)
            every { snapshots.referencingPrograms(any()) } returns listOf(1)
            for ((name, changed) in variants(program)) {
                withClue("Program.$name") { fingerprintWith { every { programs.find(any()) } returns changed } shouldNotBe baseline }
            }
            for ((name, changed) in variants(source)) {
                withClue("ExecutedFile.$name") { fingerprintWith { every { rows.executed(any()) } returns changed } shouldNotBe baseline }
            }
            for ((name, changed) in variants(split)) {
                withClue("SplittedFile.$name") {
                    fingerprintWith { every { rows.splits(any()) } returns listOf(changed) } shouldNotBe baseline
                }
            }
            for ((name, changed) in variants(file)) {
                withClue("CreatedFileSnapshot.$name") {
                    fingerprintWith { every { rows.created(any()) } returns listOf(changed) } shouldNotBe baseline
                }
            }
        }
        expect("executed and split changes invalidate snapshots") {
            val plan = command.preview(request)
            every { rows.executed(10) } returns source.copy(file = "changed")
            command
                .apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint)))
                .outcomes
                .single()
                .status shouldBe
                BulkOutcomeStatus.STALE
            every { rows.executed(10) } returns source
            every { rows.splits(10) } returns listOf(split.copy(status = SplittedFile.Status.REGISTERED))
            command
                .apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint)))
                .outcomes
                .single()
                .status shouldBe
                BulkOutcomeStatus.STALE
        }
        expect("shared normalized paths and shared executed records are refused and excluded from estimates") {
            every { snapshots.allCreatedFiles() } returns listOf(file, file.copy(id = 99, file = "BASE\\A\\ALPHA\\VIDEO.MP4"))
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
            every { snapshots.allCreatedFiles() } returns listOf(file)
            every { snapshots.referencingPrograms(10) } returns listOf(1, 2)
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
            every { snapshots.allCreatedFiles() } returns listOf(file, file.copy(id = 99, file = "base/B/Beta/video.mp4"))
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
            every { rows.created(10) } returns listOf(file.copy(file = "../x"))
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
                every { rows.created(10) } returns listOf(file.copy(file = path))
                command
                    .preview(request)
                    .targets
                    .single()
                    .eligible shouldBe false
            }
            every { rows.created(10) } returns listOf(file)
            every { snapshots.allCreatedFiles() } returns listOf(file, file.copy(id = 99, file = file.file + "."))
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
            every { rows.created(10) } returns listOf(file.copy(status = CreatedFile.Status.ENCODE_SUCCESS))
            command
                .preview(request)
                .targets
                .single()
                .eligible shouldBe false
        }
        expect("partial failure is returned per target and subsequent targets run") {
            val batch = BulkRequest(listOf(1, 2), BulkAction.DELETE)
            every { programs.find(2) } returns program.copy(id = 2)
            every { snapshots.referencingPrograms(10) } returns emptyList()
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
            every { rows.created(10) } returns emptyList()
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
            every { rows.executed(11) } returns source.copy(id = 11)
            every { rows.splits(11) } returns listOf(split.copy(id = 21, executedFileId = 11))
            every { rows.created(10) } answers { listOf(files[0]) }
            every { rows.created(11) } answers { listOf(files[1]) }
            every { snapshots.referencingPrograms(11) } returns listOf(2)
            every { snapshots.allCreatedFiles() } answers { files.toList() }
            every { snapshots.createdFilesOfPrograms(any()) } answers { files.toList() }
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
            every { snapshots.createdFilesOfPrograms(ids) } returns ids.map { file }
            command.preview(BulkRequest(ids, BulkAction.MOVE, "beta")).targets.size shouldBe 100
            verify(exactly = 1) {
                snapshots.allCreatedFiles()
                snapshots.createdFilesOfPrograms(ids)
            }
            verify(exactly = 100) { snapshots.programSnapshot(any()) }
        }
        expect("a lock wait timeout reports a busy library without mutating anything") {
            val plan = command.preview(request)
            every { locks.lockCreatedFiles() } throws CannotAcquireLockException("Lock wait timeout exceeded")
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
            every { locks.lockProgramGraph(any()) } throws IllegalStateException("db down")
            val outcome = command.apply(BulkApplyRequest(request, mapOf(1L to plan.targets.single().fingerprint))).outcomes.single()
            outcome.status shouldBe BulkOutcomeStatus.FAILED
            outcome.message.contains("nothing was changed") shouldBe true
            verify(exactly = 0) { programs.delete(any(), any()) }
        }
        expect("an interrupt stops later targets from starting") {
            val batch = BulkRequest(listOf(1, 2), BulkAction.DELETE)
            every { programs.find(2) } returns program.copy(id = 2)
            every { snapshots.referencingPrograms(10) } returns emptyList()
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
                locks.lockCreatedFiles()
                locks.lockProgramGraph(1)
                snapshots.programSnapshot(1)
                rows.executed(10)
                programs.delete(program, false)
            }
            verify(exactly = 0) { locks.lockWholeLedger() }
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
                locks.lockCreatedFiles()
                locks.lockWholeLedger()
                locks.lockProgramGraph(1)
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
                locks.lockProgramGraph(any())
                programs.delete(any(), any())
            }
        }
        expect("preview snapshots publish catalog's stored-row snapshot types") {
            val snapshot =
                command
                    .preview(request)
                    .targets
                    .single()
                    .snapshot
                    .shouldNotBeNull()
            snapshot.executed shouldBe source
            snapshot.splitFiles shouldBe listOf(split)
            snapshot.createdFiles shouldBe listOf(file)
            snapshot.createdFiles
                .single()
                .status.name shouldBe file.status.name
        }
        expect("missing target is a per-target result") {
            val plan = command.preview(request)
            every { locks.lockProgramGraph(1) } returns null
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
