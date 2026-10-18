package me.pinfort.tsvideos.core.command

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.domain.DuplicateKeepCriterion
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DuplicateReviewCommandTest : ExpectSpec() {
    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var programs: ProgramMapper

    @Autowired private lateinit var files: CreatedFileMapper

    private fun command() = DuplicateReviewCommand(DuplicateProgramCommand(programs), files)

    init {
        beforeTest { clear() }
        // Leave the shared container empty so later specs' fixed names and IDs don't collide.
        afterTest { clear() }
        expect("empty and deleted recordings do not produce groups") {
            command().review().groups shouldBe emptyList()
            seed()
            sql(listOf("DELETE FROM program WHERE id=2"))
            command().review().groups shouldBe emptyList()
        }
        expect("same detector order includes only FILE_MOVED artifacts and preserves unknown drops") {
            seed()
            sql(
                listOf(
                    "INSERT INTO splitted_file VALUES (1,1,'s1',1,1,'REGISTERED')",
                    "INSERT INTO created_file(id, splitted_file_id, file, size, mime, encoding, status) VALUES (1,1,'ready',100,'video/mp4',NULL,'FILE_MOVED'),(2,1,'pending',200,'video/mp4',NULL,'REGISTERED')",
                ),
            )
            val result = command().review().groups.single()
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
                .review()
                .groups
                .single()
                .recommendation.decisiveCriterion shouldBe DuplicateKeepCriterion.STATUS
            sql(listOf("UPDATE program SET status='COMPLETED'", "UPDATE executed_file SET duration=55 WHERE id=2"))
            command()
                .review()
                .groups
                .single()
                .recommendation.decisiveCriterion shouldBe DuplicateKeepCriterion.DURATION
            sql(listOf("UPDATE executed_file SET duration=60", "UPDATE executed_file SET size=50 WHERE id=2"))
            command()
                .review()
                .groups
                .single()
                .recommendation.decisiveCriterion shouldBe DuplicateKeepCriterion.SIZE
            sql(listOf("UPDATE executed_file SET size=100"))
            command()
                .review()
                .groups
                .single()
                .recommendation.decisiveCriterion shouldBe DuplicateKeepCriterion.ID
            command()
                .review()
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
            val group = command().review().groups.single()
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
            val group = command().review().groups.single()
            // drops: 1 → 0, 3 → 5, 2 → unknown
            group.members.map { it.program.id } shouldBe listOf(1L, 3L, 2L)
            group.recommendation.comparedWithProgramId shouldBe 3L
            group.recommendation.decisiveCriterion shouldBe DuplicateKeepCriterion.DROPS
            group.members.map { member -> member.availableFiles.map { it.id } } shouldBe listOf(emptyList(), listOf(3L, 4L), listOf(2L))
        }
    }

    private fun seed() =
        sql(
            listOf(
                "INSERT INTO executed_file VALUES (1,'a',0,100,'2026-01-01','ch','a','ch',60,'SPLITTED'),(2,'b',-1,100,'2026-01-01','ch','b','ch',60,'SPLITTED')",
                "INSERT INTO program VALUES (1,'a',1,'COMPLETED'),(2,'b',2,'COMPLETED')",
            ),
        )

    private fun clear() = sql(listOf("created_file", "splitted_file", "program", "executed_file").map { "DELETE FROM $it" })

    private fun sql(statements: List<String>) {
        dataSource.connection.use { c ->
            statements.forEach { query -> c.createStatement().use { it.execute(query) } }
            c.commit()
        }
    }
}
