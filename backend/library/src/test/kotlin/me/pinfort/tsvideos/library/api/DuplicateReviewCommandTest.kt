package me.pinfort.tsvideos.library.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.catalog.api.ProgramInventory
import me.pinfort.tsvideos.library.TestLoggerConfig
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import me.pinfort.tsvideos.library.domain.DuplicateGroupIdentity
import me.pinfort.tsvideos.library.domain.DuplicateKeepCriterion
import me.pinfort.tsvideos.library.domain.StaleDuplicateGroupException
import me.pinfort.tsvideos.library.persistence.DuplicateDismissalMapper
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@Import(ProgramInventory::class, CreatedFileCatalog::class, TestLoggerConfig::class)
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DuplicateReviewCommandTest : ExpectSpec() {
    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var programs: ProgramInventory

    @Autowired private lateinit var files: CreatedFileCatalog

    @Autowired private lateinit var dismissals: DuplicateDismissalMapper

    // A new instance per call, so what survives between calls is only what the database keeps.
    private fun command() = DuplicateReviewCommand(DuplicateProgramCommand(programs), files, dismissals)

    init {
        beforeTest { clear() }
        // Leave the shared container empty so later specs' fixed names and IDs don't collide.
        afterTest { clear() }
        expect("empty and deleted recordings do not produce groups") {
            command().review(includeHidden = true).groups shouldBe emptyList()
            seed()
            sql(listOf("DELETE FROM program WHERE id=2"))
            command().review(includeHidden = true).groups shouldBe emptyList()
        }
        expect("same detector order includes only FILE_MOVED artifacts and preserves unknown drops") {
            seed()
            sql(
                listOf(
                    "INSERT INTO splitted_file VALUES (1,1,'s1',1,1,'REGISTERED')",
                    "INSERT INTO created_file(id, splitted_file_id, file, size, mime, encoding, status) VALUES (1,1,'ready',100,'video/mp4',NULL,'FILE_MOVED'),(2,1,'pending',200,'video/mp4',NULL,'REGISTERED')",
                ),
            )
            val result = command().review(includeHidden = true).groups.single()
            result.members.map { it.program.id } shouldBe
                DuplicateProgramCommand(programs)
                    .detect()
                    .single()
                    .programs
                    .map { it.id }
            result.recommendedId shouldBe 1L
            result.members[1].program.drops shouldBe -1
            result.members
                .first()
                .availableFiles
                .map { it.id } shouldBe listOf(1L)
            result.recommendation.decisiveCriterion shouldBe DuplicateKeepCriterion.DROPS
            result.recommendation.drops shouldBe 0
            result.recommendation.orderedCriteria shouldBe DuplicateKeepCriterion.entries
        }
        expect("recommendation explanation follows every existing tie-break criterion") {
            seed()
            sql(listOf("UPDATE executed_file SET drops=0", "UPDATE program SET status='REGISTERED' WHERE id=2"))
            command()
                .review(includeHidden = true)
                .groups
                .single()
                .recommendation.decisiveCriterion shouldBe DuplicateKeepCriterion.STATUS
            sql(listOf("UPDATE program SET status='COMPLETED'", "UPDATE executed_file SET duration=55 WHERE id=2"))
            command()
                .review(includeHidden = true)
                .groups
                .single()
                .recommendation.decisiveCriterion shouldBe DuplicateKeepCriterion.DURATION
            sql(listOf("UPDATE executed_file SET duration=60", "UPDATE executed_file SET size=50 WHERE id=2"))
            command()
                .review(includeHidden = true)
                .groups
                .single()
                .recommendation.decisiveCriterion shouldBe DuplicateKeepCriterion.SIZE
            sql(listOf("UPDATE executed_file SET size=100"))
            command()
                .review(includeHidden = true)
                .groups
                .single()
                .recommendation.decisiveCriterion shouldBe DuplicateKeepCriterion.ID
            command()
                .review(includeHidden = true)
                .groups
                .single()
                .recommendation.comparedWithProgramId shouldBe 2L
        }
        expect("ranks REGISTERED above ERROR when status decides") {
            seed()
            sql(
                listOf(
                    "UPDATE executed_file SET drops=0",
                    "UPDATE program SET status='ERROR' WHERE id=1",
                    "UPDATE program SET status='REGISTERED' WHERE id=2",
                ),
            )
            val group = command().review(includeHidden = true).groups.single()
            group.recommendedId shouldBe 2L
            group.recommendation.decisiveCriterion shouldBe DuplicateKeepCriterion.STATUS
            group.recommendation.comparedWithProgramId shouldBe 1L
        }
        expect("a three-member group keeps detector order, explains against the runner-up and loads each member's files") {
            seed()
            sql(
                listOf(
                    "INSERT INTO executed_file VALUES (3,'c',5,100,'2026-01-01','ch','c','ch',60,'SPLITTED')",
                    "INSERT INTO program VALUES (3,'c',3,'COMPLETED')",
                    "INSERT INTO splitted_file VALUES (1,1,'s1',1,1,'REGISTERED'),(2,2,'s2',1,1,'REGISTERED'),(3,3,'s3',1,1,'REGISTERED')",
                    "INSERT INTO created_file(id, splitted_file_id, file, size, mime, encoding, status) VALUES " +
                        "(4,3,'c2',10,'video/mp4',NULL,'FILE_MOVED'),(3,3,'c1',10,'video/mp4',NULL,'FILE_MOVED')," +
                        "(2,2,'b1',10,'video/mp4',NULL,'FILE_MOVED'),(1,1,'a1',10,'video/mp4',NULL,'ENCODE_SUCCESS')",
                ),
            )
            val group = command().review(includeHidden = true).groups.single()
            // drops: 1 → 0, 3 → 5, 2 → unknown
            group.members.map { it.program.id } shouldBe listOf(1L, 3L, 2L)
            group.recommendation.comparedWithProgramId shouldBe 3L
            group.recommendation.decisiveCriterion shouldBe DuplicateKeepCriterion.DROPS
            group.members.map { member -> member.availableFiles.map { it.id } } shouldBe listOf(emptyList(), listOf(3L, 4L), listOf(2L))
        }
        expect("a hidden group survives a new command instance, is left out by default and comes back when restored") {
            seed()
            command().hide(same(1, 2))
            visibleIds() shouldBe emptyList()
            val all = command().review(includeHidden = true).groups
            all.single().hidden shouldBe true
            all.single().members.map { it.program.id } shouldBe listOf(1L, 2L)
            command().restore(same(1, 2))
            command()
                .review(includeHidden = false)
                .groups
                .single()
                .hidden shouldBe false
            dismissalRows() shouldBe 0
        }
        expect("hide and restore are idempotent and accept the members in any order") {
            seed()
            command().hide(same(2, 1))
            command().hide(same(1, 2))
            dismissalRows() shouldBe 1
            visibleIds() shouldBe emptyList()
            command().restore(same(1, 2))
            command().restore(same(2, 1))
            dismissalRows() shouldBe 0
            visibleIds() shouldBe listOf(listOf(1L, 2L))
        }
        expect("a changed keep ranking keeps the group hidden") {
            seed()
            command().hide(same(1, 2))
            sql(listOf("UPDATE executed_file SET drops=9 WHERE id=1", "UPDATE executed_file SET drops=0 WHERE id=2"))
            val group = command().review(includeHidden = true).groups.single()
            group.recommendedId shouldBe 2L
            group.hidden shouldBe true
            visibleIds() shouldBe emptyList()
        }
        expect("a new member, a removed member or a changed reason shows the group for review again") {
            seed()
            sql(
                listOf(
                    "INSERT INTO executed_file VALUES (3,'c',5,100,'2026-01-01','ch','c','ch',60,'SPLITTED')",
                    "INSERT INTO program VALUES (3,'c',3,'COMPLETED')",
                ),
            )
            command().hide(same(1, 2, 3))
            visibleIds() shouldBe emptyList()
            sql(listOf("DELETE FROM program WHERE id=3"))
            // The stored {1,2,3} must not shrink into the now detected {1,2}.
            visibleIds() shouldBe listOf(listOf(1L, 2L))
            command().hide(same(1, 2))
            sql(
                listOf(
                    "INSERT INTO executed_file VALUES (4,'d',5,100,'2026-01-01','ch','d','ch',60,'SPLITTED')",
                    "INSERT INTO program VALUES (4,'d',4,'COMPLETED')",
                ),
            )
            visibleIds() shouldBe listOf(listOf(1L, 2L, 4L))
            sql(listOf("DELETE FROM program WHERE id=4"))
            visibleIds() shouldBe emptyList()
            // Same members, but now overlapping instead of starting together.
            sql(listOf("UPDATE executed_file SET recorded_at='2026-01-01 00:00:05' WHERE id=2"))
            command()
                .review(includeHidden = false)
                .groups
                .single()
                .reason shouldBe DuplicateGroup.Reason.OVERLAP
        }
        expect("malformed requests are rejected and partial, extended, wrong-reason or unknown groups are stale") {
            seed()
            sql(
                listOf(
                    "INSERT INTO executed_file VALUES (3,'c',5,100,'2026-01-01','ch','c','ch',60,'SPLITTED')",
                    "INSERT INTO program VALUES (3,'c',3,'COMPLETED'),(9,'other',3,'COMPLETED')",
                ),
            )
            listOf(same(), same(1), same(1, 1, 2), same(0, 1), same(-1, 1)).forEach { invalid ->
                shouldThrow<IllegalArgumentException> { command().hide(invalid) }
                shouldThrow<IllegalArgumentException> { command().restore(invalid) }
            }
            listOf(
                same(1, 2),
                same(1, 2, 3, 9, 99),
                DuplicateGroupIdentity(DuplicateGroup.Reason.OVERLAP, listOf(1, 2, 3, 9)),
                same(7, 8),
            ).forEach { stale ->
                shouldThrow<StaleDuplicateGroupException> { command().hide(stale) }
                shouldThrow<StaleDuplicateGroupException> { command().restore(stale) }
            }
            dismissalRows() shouldBe 0
            visibleIds() shouldBe listOf(listOf(1L, 2L, 3L, 9L))
        }
        expect("hiding changes no recording or file") {
            seed()
            sql(
                listOf(
                    "INSERT INTO splitted_file VALUES (1,1,'s1',1,1,'REGISTERED')",
                    "INSERT INTO created_file(id, splitted_file_id, file, size, mime, encoding, status) VALUES (1,1,'ready',100,'video/mp4',NULL,'FILE_MOVED')",
                ),
            )
            val before = programs.recordedPrograms() to files.movedFilesByRecording(listOf(1, 2))
            command().hide(same(1, 2))
            (programs.recordedPrograms() to files.movedFilesByRecording(listOf(1, 2))) shouldBe before
        }
    }

    private fun seed() =
        sql(
            listOf(
                "INSERT INTO executed_file VALUES (1,'a',0,100,'2026-01-01','ch','a','ch',60,'SPLITTED'),(2,'b',-1,100,'2026-01-01','ch','b','ch',60,'SPLITTED')",
                "INSERT INTO program VALUES (1,'a',1,'COMPLETED'),(2,'b',2,'COMPLETED')",
            ),
        )

    private fun clear() =
        sql(listOf("library_duplicate_dismissal", "created_file", "splitted_file", "program", "executed_file").map { "DELETE FROM $it" })

    private fun same(vararg ids: Long) = DuplicateGroupIdentity(DuplicateGroup.Reason.SAME_BROADCAST, ids.toList())

    private fun visibleIds() =
        command().review(includeHidden = false).groups.map { group ->
            group.members.map { it.program.id }.sorted()
        }

    private fun dismissalRows(): Int =
        dataSource.connection.use { c ->
            c.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM library_duplicate_dismissal").use { rows ->
                    rows.next()
                    rows.getInt(1)
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
