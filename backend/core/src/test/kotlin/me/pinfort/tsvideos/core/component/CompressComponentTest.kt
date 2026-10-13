package me.pinfort.tsvideos.core.component

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.GZIPInputStream

class CompressComponentTest :
    ExpectSpec({
        val compressComponent = CompressComponent(LoggerFactory.getLogger(CompressComponentTest::class.java))

        expect("interruption removes only the exclusively created partial output") {
            val original = File.createTempFile("interrupt-compress", ".ts").apply { writeBytes(ByteArray(32000)) }
            val output = File(original.path + ".gz")
            try {
                io.kotest.assertions.throwables.shouldThrow<InterruptedException> {
                    compressComponent.compress(original, output, false) { _, _ -> throw InterruptedException() }
                }
                output.exists() shouldBe false
                original.length() shouldBe 32000
                output.writeText("existing")
                compressComponent.compress(original, output, false) { _, _ -> throw InterruptedException() } shouldBe false
                output.readText() shouldBe "existing"
            } finally {
                original.delete()
                output.delete()
            }
        }
        context("compress") {
            expect("compresses the original file into a gzip file readable back to the original bytes") {
                val original = File.createTempFile("compress-test", ".m2ts")
                original.writeBytes("hello ts video data".toByteArray())
                val compressed = File.createTempFile("compress-test", ".m2ts.gz")
                compressed.delete()

                val result = compressComponent.compress(original, compressed)

                result shouldBe true
                compressed.exists() shouldBe true
                GZIPInputStream(compressed.inputStream()).use { it.readBytes() } shouldBe "hello ts video data".toByteArray()
            }

            expect("returns false and does not overwrite when the compressed file already exists") {
                val original = File.createTempFile("compress-test", ".m2ts")
                original.writeBytes("hello".toByteArray())
                val compressed = File.createTempFile("compress-test", ".m2ts.gz")
                compressed.writeBytes("preexisting".toByteArray())

                val result = compressComponent.compress(original, compressed)

                result shouldBe false
                compressed.readBytes() shouldBe "preexisting".toByteArray()
            }

            expect("overwrites when force is true") {
                val original = File.createTempFile("compress-test", ".m2ts")
                original.writeBytes("hello".toByteArray())
                val compressed = File.createTempFile("compress-test", ".m2ts.gz")
                compressed.writeBytes("preexisting".toByteArray())

                val result = compressComponent.compress(original, compressed, force = true)

                result shouldBe true
                GZIPInputStream(compressed.inputStream()).use { it.readBytes() } shouldBe "hello".toByteArray()
            }

            expect("reports progress up to the total byte count while compressing") {
                val original = File.createTempFile("compress-test", ".m2ts")
                original.writeBytes("hello ts video data".toByteArray())
                val compressed = File.createTempFile("compress-test", ".m2ts.gz")
                compressed.delete()
                val reported = mutableListOf<Pair<Long, Long>>()

                val result =
                    compressComponent.compress(original, compressed, false) { transferred, total ->
                        reported.add(
                            transferred to total,
                        )
                    }

                result shouldBe true
                reported.isEmpty() shouldBe false
                reported.first() shouldBe (0L to original.length())
                reported.last() shouldBe (original.length() to original.length())
            }
        }
    })
