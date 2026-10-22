package me.pinfort.tsvideos.catalog.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

class NormalizeComponentTest :
    ExpectSpec({
        val normalizeComponent = NormalizeComponent()

        context("normalize") {
            expect("success") {
                normalizeComponent.normalize("\\/:*?\"<>|~‼０１２３４５６７８９ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ") shouldBe
                    "￥／：＊？”＜＞｜～!!0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            }
        }

        context("normalizeSpecialCharacters") {
            expect("success") {
                normalizeComponent.normalizeSpecialCharacters("\\/:*?\"<>|~‼") shouldBe "￥／：＊？”＜＞｜～!!"
            }
        }

        context("normalizeAlphaNumeric") {
            expect("success") {
                normalizeComponent.normalizeAlphaNumeric("０１２３４５６７８９ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ") shouldBe
                    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            }
        }
    })
