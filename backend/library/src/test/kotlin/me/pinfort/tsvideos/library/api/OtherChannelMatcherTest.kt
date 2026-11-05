package me.pinfort.tsvideos.library.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The other-channel comparison key (#209). Titles marked "actual" are copied from saved search output (see
 * issue-209-fixture.json); the others are made up to pin down a boundary the saved data does not exercise.
 */
class OtherChannelMatcherTest :
    ExpectSpec({
        fun key(title: String) = OtherChannelMatcher.key(title)

        context("comparison key") {
            expect("actual: the BSBS01_0 frame name is dropped, so it matches the GR16 title of the same episode") {
                val bs = key("[新]アニメA・放送開始10周年　魔法少女まどか☆マギカ　＃1.m2ts")
                val gr = key("魔法少女まどか☆マギカ#1.m2ts")
                bs.shouldNotBeNull()
                bs shouldBe gr
                bs.display shouldBe "魔法少女まどか☆マギカ #1"
                key("アニメA・放送開始10周年　魔法少女まどか☆マギカ　＃11.m2ts") shouldBe key("魔法少女まどか☆マギカ#11.m2ts")
            }

            expect("without a frame name the key is exactly the shared rebroadcast key") {
                listOf(
                    "魔法少女まどか☆マギカ#1.m2ts",
                    "[新]マリッジトキシン #01[SV：KT].m2ts",
                    "アニメ　BanG Dream! It's MyGO!!!!![再]　＃１[ID：10203][SV：KT].m2ts",
                    "番組 #1「副題」[字]",
                ).forEach { key(it) shouldBe RebroadcastMatcher.key(it) }
            }

            expect("the frame-name correction never changes the rebroadcast key") {
                RebroadcastMatcher.key("アニメA・放送開始10周年　魔法少女まどか☆マギカ　＃1.m2ts")?.display shouldBe
                    "アニメA・放送開始10周年 魔法少女まどか☆マギカ #1"
            }

            expect("a frame name alone, glued to the title or in the middle is not dropped") {
                key("アニメA・放送開始10周年　＃1.m2ts").shouldBeNull()
                key("アニメA・放送開始10周年魔法少女まどか☆マギカ #1") shouldNotBe key("魔法少女まどか☆マギカ #1")
                key("特集 アニメA・放送開始10周年 魔法少女まどか☆マギカ #1") shouldNotBe key("魔法少女まどか☆マギカ #1")
            }

            expect("actual: a re-edited edition keeps its own title and does not match the series episode") {
                key("[新]日５「魔法少女まどか☆マギカ　始まりの物語／永遠の物語」TV Edition　＃０１[字][デ][SV：KT].m2ts") shouldNotBe
                    key("魔法少女まどか☆マギカ#1.m2ts")
            }

            expect("actual: a title without an episode number, or another series name for the season, has no match") {
                key("マギアレコード　魔法少女まどか☆マギカ外伝　２ｎｄ　ＳＥＡＳＯＮ　－覚醒前夜－[終].m2ts").shouldBeNull()
                key("新プロジェクトＸ　英国を救った高速鉄道～崖っぷち鉄道車両部門の逆転劇～[解][字][再][SV：KT].m2ts").shouldBeNull()
                key("[新]Dr．STONE第4期 #13[SV：KT].m2ts") shouldNotBe key("[新]Dr．STONE SCIENCE FUTURE　＃13[ID：NULL][SV：KT].m2ts")
            }

            expect("actual: a subtitle and promotion present on one channel only is not ignored") {
                key("[新]マリッジトキシン #01[SV：KT].m2ts") shouldNotBe
                    key("マリッジトキシン　＃０１[再]　「毒使いの婚活」毒をもって恋を制す！[SV：KT].m2ts")
            }
        }
    })
