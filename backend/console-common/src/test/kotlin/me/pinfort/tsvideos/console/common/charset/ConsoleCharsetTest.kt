package me.pinfort.tsvideos.console.common.charset

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.nio.charset.Charset

class ConsoleCharsetTest :
    DescribeSpec({
        val ms932 = Charset.forName("MS932")

        describe("resolveLogCharset") {
            it("follows the stderr charset when not overridden") {
                ConsoleCharset.resolveLogCharset(null, ms932) shouldBe ms932
                ConsoleCharset.resolveLogCharset("  ", ms932) shouldBe ms932
            }
            it("uses the override") {
                ConsoleCharset.resolveLogCharset("UTF-8", ms932) shouldBe Charsets.UTF_8
            }
            it("falls back for unknown or illegal names") {
                ConsoleCharset.resolveLogCharset("no-such-charset", ms932) shouldBe ms932
                ConsoleCharset.resolveLogCharset("bad name!", ms932) shouldBe ms932
            }
        }
    })
