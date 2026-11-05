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
 * Regression over the #209 real-data fixture through the production detector (DuplicateProgramCommand.detect). Always
 * runs. Every row is an actual recording; an expected group means the rules pair the rows, not verified identical video.
 */
class OtherChannelFixtureTest :
    ExpectSpec({
        val root: JsonNode =
            RebroadcastData.mapper.readTree(
                checkNotNull(javaClass.getResourceAsStream("/rebroadcast/issue-209-fixture.json")) { "issue-209-fixture.json missing" },
            )
        val programs = RebroadcastData.programs(root)

        fun pairs(name: String): Set<Set<Long>> =
            checkNotNull(root[name]) { "fixture has no \"$name\"" }
                .items()
                .map { group -> group.items().map { it.asLong() }.toSet() }
                .toSet()

        val expected = pairs("expectedOtherChannelGroups")
        val notTogether = pairs("notTogether")

        fun shape(groups: List<DuplicateGroup>) = groups.map { group -> group.reason to group.programs.map { it.id } }

        expect("every row is actual, ids are unique and every referenced id is present") {
            root["programs"].items().forEach { row ->
                row["source"].asString() shouldBe "actual"
                row["copied"].items().map { it.asString() } shouldContainAll listOf("id", "channel", "recordedAt", "duration", "title")
            }
            programs.map { it.id }.distinct() shouldHaveSize programs.size
            val ids = programs.map { it.id }.toSet()
            (expected.flatten() + notTogether.flatten()).filter { it !in ids }.shouldBeEmpty()
            notTogether.forEach { it shouldHaveSize 2 }
        }

        expect("exactly the 11 まどか☆マギカ pairs are OTHER_CHANNEL, one channel each, and nothing else is grouped") {
            val groups = RebroadcastData.detect(programs)

            expected shouldHaveSize 11
            RebroadcastData.idSets(groups, DuplicateGroup.Reason.OTHER_CHANNEL) shouldBe expected
            groups.filter { it.reason != DuplicateGroup.Reason.OTHER_CHANNEL }.shouldBeEmpty()
            groups.all { group -> group.programs.map { it.channel }.toSet() == setOf("GR16", "BSBS01_0") } shouldBe true
            groups.map { it.comparisonTitle }.toSet() shouldBe (1..11).map { "魔法少女まどか☆マギカ #$it" }.toSet()
            RebroadcastData.sharedMembers(groups).shouldBeEmpty()
        }

        expect("negative examples never share a group: editions, titles without an episode, other wording and lengths") {
            val groups = RebroadcastData.detect(programs)
            val groupOf = groups.flatMap { group -> group.programs.map { it.id to group } }.toMap()
            notTogether.forEach { pair ->
                val (a, b) = pair.toList()
                if (groupOf[a] != null) groupOf[a] shouldNotBe groupOf[b]
            }
        }

        expect("the result does not depend on input order and earlier stages match the pre-#208 baseline") {
            val groups = RebroadcastData.detect(programs)

            shape(RebroadcastData.detect(programs.reversed())) shouldBe shape(groups)
            shape(RebroadcastData.detect(programs.shuffled(kotlin.random.Random(209)))) shouldBe shape(groups)
            shape(groups.filter { it.reason != DuplicateGroup.Reason.OTHER_CHANNEL }) shouldBe shape(RebroadcastData.baseline(programs))
        }

        expect("the #208 fixtures gain no other-channel group") {
            listOf("issue-208-fixture.json", "issue-208-synthetic.json").forEach { name ->
                val fixture =
                    RebroadcastData.mapper.readTree(checkNotNull(javaClass.getResourceAsStream("/rebroadcast/$name")) { "$name missing" })
                RebroadcastData
                    .detect(RebroadcastData.programs(fixture))
                    .filter { it.reason == DuplicateGroup.Reason.OTHER_CHANNEL }
                    .shouldBeEmpty()
            }
        }
    })
