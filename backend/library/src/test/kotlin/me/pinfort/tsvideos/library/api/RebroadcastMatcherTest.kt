package me.pinfort.tsvideos.library.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import me.pinfort.tsvideos.catalog.domain.Program
import java.time.LocalDateTime

/**
 * Titles below are RECONSTRUCTED from the shapes reported in the #208 investigation comment (marker placement,
 * management suffixes, episode notation). They are not raw recorder data; subtitles and IDs are placeholders.
 */
class RebroadcastMatcherTest :
    ExpectSpec({
        fun key(title: String) = RebroadcastMatcher.key(title)

        context("comparison key") {
            expect("reconstructed MyGO #1 pair: [新] vs [再] and differing terminal [ID][SV].m2ts fields match") {
                val first = key("BanG Dream! It's MyGO!!!!! #1「副題」[新][字][ID：10194][SV：KT].m2ts")
                val second = key("BanG Dream! It's MyGO!!!!! #1「副題」[再][字][ID：10203][SV：KT].m2ts")
                first.shouldNotBeNull()
                first shouldBe second
                first.episode shouldBe 1
                first.display shouldBe "BanG Dream! It's MyGO!!!!! #1 「副題」"
            }

            expect("reconstructed SYNDUALITY pair without any rebroadcast marker still matches") {
                key("SYNDUALITY Noir 第3話「副題」[字]") shouldBe key("SYNDUALITY Noir 第3話「副題」")
            }

            expect("[ID:NULL], [SERVER:...] and full-width text are normalized by NFKC and the terminal-field rule") {
                key("ＡＢＣ　＃０２[ID：NULL]") shouldBe key("ABC #2 [SERVER:x].M2TS")
                key("ABC #2")?.episode shouldBe 2
            }

            expect("第N話 and #N are the same episode notation") {
                key("番組 第12話") shouldBe key("番組 #12")
            }

            expect("only terminal management fields are stripped, not arbitrary brackets or numbers") {
                key("番組 [ID:1] #1") shouldNotBe key("番組 #1")
                key("番組 [生] #1") shouldNotBe key("番組 #1")
                key("番組2 #1") shouldNotBe key("番組3 #1")
            }

            expect("part markers [前]/[後] are kept, so 前編 and 後編 differ") {
                key("番組 #1 [前]") shouldNotBe key("番組 #1 [後]")
            }

            expect("only [新]/[再]/[字]/[終] are stripped, wherever they appear") {
                key("[新]番組 #1[字]") shouldBe key("番組 #1")
                key("番組 [再] #1 [終]") shouldBe key("番組 #1")
            }

            expect("audio and edition markers are kept, so dubbed, bilingual and described editions stay apart") {
                listOf("[吹]", "[二]", "[多]", "[解]", "[双]", "[手]", "[デ]", "[無]", "[料]").forEach { edition ->
                    val original = key("海外ドラマ #1「副題」[字]")
                    val other = key("海外ドラマ #1「副題」$edition")
                    original.shouldNotBeNull()
                    other.shouldNotBeNull()
                    other shouldNotBe original
                }
                key("海外ドラマ #1[吹]") shouldNotBe key("海外ドラマ #1[二]")
                key("海外ドラマ #1[吹][再]") shouldBe key("海外ドラマ #1[吹]")
            }

            expect("an episode with no program title left is rejected") {
                listOf(
                    "#1",
                    "第1話",
                    "[再]第1話[ID:1].m2ts",
                    "[新][字] #1「副題」",
                    "[生] #1",
                    "  #1 [ID:NULL]",
                ).forEach { key(it).shouldBeNull() }
            }

            expect("subtitle punctuation after a single episode is preserved and still matches") {
                listOf(
                    "番組 #1「A・B」",
                    "番組 #1 ~旅立ち~",
                    "番組 #1〜二人の約束〜",
                    "番組 #1 - 副題",
                    "番組 第1話「あと2日」",
                    "番組 #1 とある日",
                    "Fate/stay night #1",
                    "ペルソナ5 #1",
                ).forEach { title ->
                    val actual = key(title)
                    actual.shouldNotBeNull()
                    actual.episode shouldBe 1
                    key("[再]$title") shouldBe actual
                }
            }

            expect("season, subtitle and version differences are preserved") {
                key("Dr.STONE 第3期 #7") shouldNotBe key("Dr.STONE 第4期 #7")
                key("番組 #1「A」") shouldNotBe key("番組 #1「B」")
                key("番組 #1 ディレクターズカット版") shouldNotBe key("番組 #1")
                key("番組 #1") shouldNotBe key("番組 #2")
            }

            expect("multiple episodes, ranges and binge broadcasts are rejected instead of taking the first number") {
                listOf(
                    "Dr.STONE第4期 #7,8",
                    "番組 #7・8",
                    "番組 #7-8",
                    "番組 #7〜8",
                    "番組 第1〜12話",
                    "番組 第1話・第2話",
                    "番組 #1 #2",
                    "番組 一挙放送 #1",
                    "番組 #1.5",
                    "番組 #1a",
                    "番組 第三話",
                ).forEach { key(it).shouldBeNull() }
            }

            expect("continuations that leave the first token looking single are rejected, not read as that episode") {
                listOf(
                    "番組 第1話〜3話",
                    "番組 第1話～3話",
                    "番組 第1話・2話",
                    "番組 第1話〜三話",
                    "番組 第1話と第2話",
                    "番組 #1と2",
                    "番組 #1 と #2",
                    "番組 #1及び2",
                    "番組 #1から3",
                    "番組 #1・第2話",
                    "番組 #1〜第3話",
                    "番組 #1, 2",
                    "番組 1・#2",
                    "番組 1〜第2話",
                ).forEach { key(it).shouldBeNull() }
            }

            expect("programs without an episode, Part-only specials and blank titles are rejected") {
                listOf(
                    "薬屋のひとりごと",
                    "MyGO 放送直前特番 Part1",
                    "MyGO 振り返り特番 Part2",
                    "劇場版 魔法少女まどか☆マギカ 前編",
                    "オーバーテイク！ Rd.08「副題」",
                    "[再]",
                    "",
                ).forEach { key(it).shouldBeNull() }
            }
        }

        context("groups") {
            val base = LocalDateTime.of(2023, 7, 1, 0, 30)

            fun program(
                id: Long,
                recordedAt: LocalDateTime,
                duration: Double = 1800.0,
                title: String = "番組 #1",
                channel: String = "BSBS13_0",
            ) = Program(id, "name$id", id, Program.Status.COMPLETED, 0, 100, recordedAt, channel, title, "BS", duration)

            fun ids(programs: List<Program>) = RebroadcastMatcher.groups(programs).map { (_, members) -> members.map { it.id }.sorted() }

            expect("threshold boundaries: exactly 5 seconds and exactly 0.995 match, just beyond does not") {
                // 1000 s: a 5 s difference is a ratio of 0.995 exactly.
                ids(listOf(program(1, base, 1000.0), program(2, base.plusDays(90), 995.0))) shouldBe listOf(listOf(1L, 2L))
                ids(listOf(program(1, base, 1000.0), program(2, base.plusDays(90), 994.99))).shouldBeEmpty()
                // 1800 s: 5 s is within the ratio, 5.01 s is not within the difference.
                ids(listOf(program(1, base, 1800.0), program(2, base.plusDays(90), 1805.0))) shouldBe listOf(listOf(1L, 2L))
                ids(listOf(program(1, base, 1800.0), program(2, base.plusDays(90), 1805.01))).shouldBeEmpty()
            }

            expect("short programs are bounded by the ratio, not only the 5 second difference") {
                // 300 s: 1.5 s difference is ratio 0.995; 2 s is within 5 s but below the ratio.
                ids(listOf(program(1, base, 300.0), program(2, base.plusDays(7), 298.5))) shouldBe listOf(listOf(1L, 2L))
                ids(listOf(program(1, base, 300.0), program(2, base.plusDays(7), 298.0))).shouldBeEmpty()
            }

            expect("incomplete or unknown recordings never match") {
                ids(listOf(program(1, base, 1800.0), program(2, base.plusDays(7), 1200.0))).shouldBeEmpty()
                ids(listOf(program(1, base, 0.0), program(2, base.plusDays(7), 0.0))).shouldBeEmpty()
                ids(listOf(program(1, base, Double.NaN), program(2, base.plusDays(7), Double.NaN))).shouldBeEmpty()
                val infinite = Double.POSITIVE_INFINITY
                ids(listOf(program(1, base, infinite), program(2, base.plusDays(7), infinite))).shouldBeEmpty()
                ids(listOf(program(1, LocalDateTime.MIN), program(2, base))).shouldBeEmpty()
            }

            expect("same start, overlapping intervals and different channels never match; touching intervals do") {
                ids(listOf(program(1, base), program(2, base))).shouldBeEmpty()
                ids(listOf(program(1, base), program(2, base.plusMinutes(29)))).shouldBeEmpty()
                ids(listOf(program(1, base), program(2, base.plusMinutes(30)))) shouldBe listOf(listOf(1L, 2L))
                // Reconstructed from the マリッジトキシン 第1話 shape: different channels (and durations) are out of scope.
                ids(
                    listOf(
                        program(36554, base, 1807.14, "マリッジトキシン 第1話「副題」", "CSCS16"),
                        program(39470, base.plusDays(200), 1685.737033, "マリッジトキシン 第1話「副題」[再]", "GR17"),
                    ),
                ).shouldBeEmpty()
                ids(listOf(program(1, base, channel = "A"), program(2, base.plusDays(7), channel = "B"))).shouldBeEmpty()
            }

            expect("three or more members are grouped only when every pair is compatible") {
                ids(listOf(program(1, base, 1800.0), program(2, base.plusDays(7), 1801.0), program(3, base.plusDays(14), 1802.0))) shouldBe
                    listOf(listOf(1L, 2L, 3L))
            }

            expect("a chain A-B, B-C without A-C is withheld entirely and does not depend on input order") {
                val chain = listOf(program(1, base, 1800.0), program(2, base.plusDays(7), 1804.0), program(3, base.plusDays(14), 1808.0))
                ids(chain).shouldBeEmpty()
                ids(chain.reversed()).shouldBeEmpty()
                ids(listOf(chain[1], chain[2], chain[0])).shouldBeEmpty()
            }

            expect("separate episodes form separate groups and the result is order independent") {
                val programs =
                    listOf(
                        program(1, base, title = "番組 #1"),
                        program(2, base.plusDays(90), title = "[再]番組 #1"),
                        program(3, base.plusDays(7), title = "番組 #2"),
                        program(4, base.plusDays(97), title = "番組 #2"),
                        program(5, base.plusDays(14), title = "番組 #3"),
                    )
                ids(programs).sortedBy { it.first() } shouldBe listOf(listOf(1L, 2L), listOf(3L, 4L))
                ids(programs.reversed()).sortedBy { it.first() } shouldBe listOf(listOf(1L, 2L), listOf(3L, 4L))
            }

            expect("programs without a single clear episode never match on title and duration alone") {
                // Reconstructed from the 薬屋のひとりごと (読売テレビ) shape: same title, weekly, near-identical durations.
                ids(
                    listOf(
                        program(1, base, 1440.0, "薬屋のひとりごと", "GR10"),
                        program(2, base.plusDays(7), 1440.5, "薬屋のひとりごと", "GR10"),
                    ),
                ).shouldBeEmpty()
                // Reconstructed from the Dr.STONE 第4期 #7,8 shape: three recordings of the same double episode.
                ids(
                    listOf(
                        program(1, base, 3600.0, "Dr.STONE第4期 #7,8"),
                        program(2, base.plusDays(30), 3600.0, "Dr.STONE第4期 #7,8"),
                        program(3, base.plusDays(60), 3600.0, "Dr.STONE第4期 #7,8"),
                    ),
                ).shouldBeEmpty()
            }

            expect("different seasons with the same episode number stay apart") {
                ids(listOf(program(1, base, title = "番組 第1期 #1"), program(2, base.plusDays(365), title = "番組 第2期 #1"))).shouldBeEmpty()
            }
        }
    })
