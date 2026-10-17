package me.pinfort.tsvideos.core.external.database.mapper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.core.command.HealthScanAlreadyRunning
import me.pinfort.tsvideos.core.command.HealthVerificationStore
import me.pinfort.tsvideos.core.command.LibraryHealthCommand
import me.pinfort.tsvideos.core.command.LibraryHealthScanCommand
import me.pinfort.tsvideos.core.command.LibraryVerificationCommand
import me.pinfort.tsvideos.core.domain.FileFinding
import me.pinfort.tsvideos.core.domain.HealthRun
import me.pinfort.tsvideos.core.domain.VerificationReport
import me.pinfort.tsvideos.core.domain.VerificationStatus
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
@Import(HealthVerificationStore::class, LibraryHealthCommand::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LibraryHealthMapperTest : ExpectSpec() {
    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var mapper: LibraryHealthMapper

    @Autowired private lateinit var store: HealthVerificationStore

    @Autowired private lateinit var health: LibraryHealthCommand

    init {
        beforeTest {
            sql(listOf("health_verification_finding", "health_verification_run", "program", "executed_file").map { "DELETE FROM $it" })
        }
        expect("GET reports errors and known drops including orphan errors without scanning") {
            sql(
                listOf(
                    "INSERT INTO executed_file VALUES(1,'a',3,1,'2026-01-01','c','a','c',1,'SPLITTED'),(2,'b',-1,1,'2026-01-01','c','b','c',1,'SPLITTED')",
                    "INSERT INTO program VALUES(1,'drops',1,'COMPLETED'),(2,'unknown',2,'COMPLETED'),(3,'orphan',999,'ERROR')",
                ),
            )
            val result = health.read()
            result.failedPrograms.map { it.id } shouldBe listOf(3L)
            result.droppedPrograms.map { it.id } shouldBe listOf(1L)
            result.latestRun shouldBe null
            result.lastCompletedRun shouldBe null
            result.summary shouldBe null
            result.findings shouldBe emptyList()
        }
        expect("completed snapshot survives new command instance and deleted program suppresses link") {
            sql(listOf("INSERT INTO program VALUES(1,'a',999,'ERROR')"))
            val id = store.start()
            val checkedAt = Instant.parse("2026-01-01T00:00:00.123456Z")
            store.complete(
                id,
                VerificationReport(
                    VerificationStatus.entries.map {
                        FileFinding(1, it.ordinal.toLong(), "path", it, checkedAt, 10, null, null)
                    },
                ),
            )
            val restarted = LibraryHealthCommand(mapper).read()
            restarted.lastCompletedRun?.id shouldBe id
            restarted.lastCompletedRun?.completedAt?.isBefore(restarted.lastCompletedRun.startedAt) shouldBe false
            restarted.summary shouldBe VerificationStatus.entries.associateWith { 1 }
            restarted.findings
                .first()
                .finding.checkedAt shouldBe checkedAt
            restarted.findings.first().programRemoved shouldBe false
            sql(listOf("DELETE FROM program"))
            health.read().findings.all { it.programRemoved && it.programName == null } shouldBe true
        }
        expect("active run is separate from prior completed and scan failure preserves old report without secrets") {
            val old = store.start()
            store.complete(old, VerificationReport(emptyList()))
            val verifier = mockk<LibraryVerificationCommand>()
            every { verifier.verify() } answers {
                health.read().latestRun?.state shouldBe HealthRun.State.RUNNING
                health.read().lastCompletedRun?.id shouldBe old
                throw IllegalStateException("password=secret")
            }
            LibraryHealthScanCommand(store, verifier).scan()
            val result = health.read()
            result.latestRun?.state shouldBe HealthRun.State.FAILED
            result.latestRun?.failureCode shouldBe "VERIFICATION_FAILED"
            result.lastCompletedRun?.id shouldBe old
            result.summary shouldBe VerificationStatus.entries.associateWith { 0 }
        }
        expect("inaccessible and missing findings are completed outcomes, not failed runs") {
            val verifier = mockk<LibraryVerificationCommand>()
            every { verifier.verify() } returns
                VerificationReport(
                    listOf(
                        FileFinding(1, 1, "a", VerificationStatus.MISSING, Instant.now(), 10, null, null),
                        FileFinding(1, 2, "b", VerificationStatus.INACCESSIBLE, Instant.now(), 10, null, null),
                    ),
                )
            LibraryHealthScanCommand(store, verifier).scan()
            health.read().latestRun?.state shouldBe HealthRun.State.COMPLETED
            health.read().summary?.get(VerificationStatus.MISSING) shouldBe 1
            health.read().summary?.get(VerificationStatus.INACCESSIBLE) shouldBe 1
        }
        expect("database failure rolls back partial findings and preserves last successful snapshot") {
            val old = store.start()
            store.complete(old, VerificationReport(emptyList()))
            val verifier = mockk<LibraryVerificationCommand>()
            every { verifier.verify() } returns
                VerificationReport(
                    listOf(
                        FileFinding(1, 1, "a", VerificationStatus.VERIFIED, Instant.now(), 1, 1, null),
                        FileFinding(1, 2, "boom", VerificationStatus.UNCHECKED, Instant.now(), 1, null, null),
                    ),
                )
            sql(listOf("ALTER TABLE health_verification_finding ADD CONSTRAINT test_reject_boom CHECK (file <> 'boom')"))
            try {
                LibraryHealthScanCommand(store, verifier).scan()
            } finally {
                sql(listOf("ALTER TABLE health_verification_finding DROP CONSTRAINT test_reject_boom"))
            }
            val result = health.read()
            result.latestRun?.state shouldBe HealthRun.State.FAILED
            mapper.findings(result.latestRun.shouldNotBeNull().id) shouldBe emptyList()
            result.lastCompletedRun?.id shouldBe old
        }
        expect("findings spanning several insert chunks are all saved in order") {
            sql(listOf("INSERT INTO program VALUES(1,'a',999,'COMPLETED')"))
            val id = store.start()
            val checkedAt = Instant.parse("2026-01-01T00:00:00Z")
            store.complete(
                id,
                VerificationReport(List(1201) { FileFinding(1, it.toLong(), "f$it", VerificationStatus.VERIFIED, checkedAt, 1, 1, null) }),
            )
            val result = health.read()
            result.findings.map { it.finding.fileId } shouldBe List(1201) { it.toLong() }
            result.findings.all { it.programName == "a" && !it.programRemoved } shouldBe true
            result.summary?.get(VerificationStatus.VERIFIED) shouldBe 1201
        }
        expect("an over-long reason is truncated instead of failing the run") {
            val id = store.start()
            val reason = "😀".repeat(300)
            store.complete(
                id,
                VerificationReport(listOf(FileFinding(1, 1, "a", VerificationStatus.INACCESSIBLE, Instant.now(), 1, null, reason))),
            )
            health.read().latestRun?.state shouldBe HealthRun.State.COMPLETED
            val saved =
                mapper
                    .findings(id)
                    .single()
                    .reason
                    .shouldNotBeNull()
            saved.codePointCount(0, saved.length) shouldBe HealthVerificationStore.REASON_MAX_CHARS
            saved.endsWith("…") shouldBe true
        }
        expect("exactly one full insert chunk and one row past it are saved") {
            listOf(500, 501).forEach { count ->
                val id = store.start()
                store.complete(
                    id,
                    VerificationReport(
                        List(count) { FileFinding(1, it.toLong(), "f$it", VerificationStatus.VERIFIED, Instant.now(), 1, 1, null) },
                    ),
                )
                mapper.findings(id).size shouldBe count
            }
        }
        expect("startup reconciliation releases an abandoned RUNNING claim and keeps completed runs") {
            val old = store.start()
            store.complete(old, VerificationReport(emptyList()))
            val abandoned = store.start()
            store.failAbandoned() shouldBe 1
            val result = health.read()
            result.latestRun?.id shouldBe abandoned
            result.latestRun?.state shouldBe HealthRun.State.FAILED
            result.latestRun?.failureCode shouldBe HealthVerificationStore.INTERRUPTED
            result.lastCompletedRun?.id shouldBe old
            store.failAbandoned() shouldBe 0
            store.start()
        }
        expect("a live scan released by another instance's startup loses only its own result") {
            val verifier = mockk<LibraryVerificationCommand>()
            var original = 0L
            var released = 0L
            every { verifier.verify() } answers {
                original =
                    health
                        .read()
                        .latestRun
                        .shouldNotBeNull()
                        .id
                // An overlapping instance boots mid-scan, releases this run and starts its own scan.
                store.failAbandoned() shouldBe 1
                released = store.start()
                VerificationReport(listOf(FileFinding(1, 1, "a", VerificationStatus.VERIFIED, Instant.now(), 1, 1, null)))
            }
            LibraryHealthScanCommand(store, verifier).scan()
            val result = health.read()
            result.latestRun?.id shouldBe released
            result.latestRun?.state shouldBe HealthRun.State.RUNNING
            result.lastCompletedRun shouldBe null
            mapper.findings(original) shouldBe emptyList()
            store.complete(released, VerificationReport(emptyList()))
            health.read().lastCompletedRun?.id shouldBe released
        }
        expect("concurrent scan claims have one winner and do not automatically reclaim a running scan") {
            val pool = Executors.newFixedThreadPool(2)
            try {
                val outcomes = pool.invokeAll(List(2) { Callable { runCatching { store.start() } } }).map { it.get() }
                outcomes.count { it.isSuccess } shouldBe 1
                outcomes.count { it.isFailure } shouldBe 1
                val verifier = mockk<LibraryVerificationCommand>()
                shouldThrow<HealthScanAlreadyRunning> { LibraryHealthScanCommand(store, verifier).scan() }
                verify(exactly = 0) { verifier.verify() }
                health.read().latestRun?.state shouldBe HealthRun.State.RUNNING
            } finally {
                pool.shutdownNow()
            }
        }
    }

    private fun sql(statements: List<String>) {
        dataSource.connection.use { c ->
            statements.forEach { query -> c.createStatement().use { it.execute(query) } }
            c.commit()
        }
    }
}
