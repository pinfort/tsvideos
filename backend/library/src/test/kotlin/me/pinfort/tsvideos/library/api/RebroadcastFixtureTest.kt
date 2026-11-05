package me.pinfort.tsvideos.library.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import me.pinfort.tsvideos.library.api.RebroadcastData.items
import me.pinfort.tsvideos.library.domain.DuplicateGroup
import tools.jackson.databind.JsonNode

/**
 * Regression over the #208 fixtures through the production detector (DuplicateProgramCommand.detect). Always runs.
 * issue-208-fixture.json holds only actual recordings (fields copied from saved tvmcli search --json output);
 * issue-208-synthetic.json holds made-up boundary cases that the real data does not exercise.
 */
class RebroadcastFixtureTest :
    ExpectSpec({
        fun load(name: String): JsonNode =
            RebroadcastData.mapper.readTree(
                checkNotNull(javaClass.getResourceAsStream("/rebroadcast/$name")) { "$name missing" },
            )

        fun pairs(
            root: JsonNode,
            name: String,
        ): Set<Set<Long>> =
            checkNotNull(root[name]) { "fixture has no \"$name\"" }
                .items()
                .map { group -> group.items().map { it.asLong() }.toSet() }
                .toSet()

        listOf(
            Triple("issue-208-fixture.json", "actual", 15),
            Triple("issue-208-synthetic.json", "synthetic", 0),
        ).forEach { (file, source, expectedCount) ->
            val root = load(file)
            val programs = RebroadcastData.programs(root)
            val expected = pairs(root, "expectedRebroadcastGroups")
            val notTogether = pairs(root, "notTogether")

            context(file) {
                expect("every row is $source, ids are unique and every referenced id is present") {
                    root["programs"].items().forEach { row ->
                        row["source"].asString() shouldBe source
                        val copied = row["copied"].items().map { it.asString() }
                        if (source == "actual") {
                            copied shouldContainAll listOf("id", "channel", "recordedAt", "duration", "title")
                        } else {
                            copied.shouldBeEmpty()
                        }
                    }
                    programs.map { it.id }.distinct() shouldHaveSize programs.size
                    val ids = programs.map { it.id }.toSet()
                    (expected.flatten() + notTogether.flatten()).filter { it !in ids }.shouldBeEmpty()
                    notTogether.forEach { it shouldHaveSize 2 }
                }

                expect("exactly the expected groups are REBROADCAST, and nothing else is grouped") {
                    val groups = RebroadcastData.detect(programs)

                    expected shouldHaveSize expectedCount
                    RebroadcastData.idSets(groups, DuplicateGroup.Reason.REBROADCAST) shouldBe expected
                    groups.filter { it.reason != DuplicateGroup.Reason.REBROADCAST }.shouldBeEmpty()
                    RebroadcastData.sharedMembers(groups).shouldBeEmpty()
                }

                expect("negative examples never share a group") {
                    val groups = RebroadcastData.detect(programs)
                    val groupOf = groups.flatMap { group -> group.programs.map { it.id to group } }.toMap()
                    notTogether.forEach { pair ->
                        val (a, b) = pair.toList()
                        if (groupOf[a] != null) groupOf[a] shouldNotBe groupOf[b]
                    }
                }

                expect("the result does not depend on input order and the first stage matches the pre-#208 baseline") {
                    fun shape(groups: List<DuplicateGroup>) = groups.map { group -> group.reason to group.programs.map { it.id } }
                    val groups = RebroadcastData.detect(programs)

                    shape(RebroadcastData.detect(programs.reversed())) shouldBe shape(groups)
                    shape(groups.filter { it.reason != DuplicateGroup.Reason.REBROADCAST }) shouldBe
                        shape(RebroadcastData.baseline(programs))
                }
            }
        }
    })
