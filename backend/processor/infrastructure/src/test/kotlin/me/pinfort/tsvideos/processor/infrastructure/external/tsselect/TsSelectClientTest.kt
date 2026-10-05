package me.pinfort.tsvideos.processor.infrastructure.external.tsselect

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsselect.TsFormatException
import java.io.File

class TsSelectClientTest :
    ExpectSpec({
        val tsSelectClient = TsSelectClient()

        // 188 バイト TS パケット列を組み立てる。全パケットは PID 0x0100・ペイロードのみ (AFC=01)、
        // ペイロード先頭バイトはパケット番号にしてあり「同一 CC の重複」判定に入らないようにしている。
        fun stream(
            count: Int,
            continuityCounterOf: (Int) -> Int,
        ): ByteArray {
            val out = ByteArray(count * 188)
            for (i in 0 until count) {
                val off = i * 188
                out[off] = 0x47
                out[off + 1] = 0x01
                out[off + 2] = 0x00
                out[off + 3] = (0x10 or (continuityCounterOf(i) and 0x0f)).toByte()
                out[off + 4] = i.toByte()
            }
            return out
        }

        fun tempTs(bytes: ByteArray): File {
            val file = File.createTempFile("tsselect-client-test", ".m2ts")
            file.deleteOnExit()
            file.writeBytes(bytes)
            return file
        }

        context("check") {
            expect("returns 0 when the continuity counter increments cleanly") {
                val file = tempTs(stream(16) { it and 0x0f })

                tsSelectClient.check(file) shouldBe 0
            }

            expect("counts every continuity-counter error across the stream") {
                // CC が毎回 2 ずつ進むので、先頭を除く 15 パケットすべてがドロップ扱いになる。
                val file = tempTs(stream(16) { (it * 2) and 0x0f })

                tsSelectClient.check(file) shouldBe 15
            }

            expect("throws when the input is not a transport stream") {
                val file = tempTs(ByteArray(512))

                shouldThrow<TsFormatException> {
                    tsSelectClient.check(file)
                }
            }
        }

        context("check with progress") {
            expect("reports progress and finishes at 100% of the file size") {
                // 8192 バイトの読み込みチャンクを跨ぐよう、十分な数のパケットを並べる。
                val file = tempTs(stream(200) { it and 0x0f })
                val events = mutableListOf<Pair<Long, Long>>()

                tsSelectClient.check(file) { bytesProcessed, totalBytes -> events += bytesProcessed to totalBytes }

                events.forEach { (_, total) -> total shouldBe file.length() }
                events.any { (processed, _) -> processed < file.length() } shouldBe true
                events.last() shouldBe (file.length() to file.length())
            }
        }
    })
