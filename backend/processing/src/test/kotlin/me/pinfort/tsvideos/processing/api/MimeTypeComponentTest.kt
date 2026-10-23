package me.pinfort.tsvideos.processing.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

class MimeTypeComponentTest :
    ExpectSpec({
        val mimeTypeComponent = MimeTypeComponent()

        context("guess") {
            expect("mp4") {
                mimeTypeComponent.guess("foo.mp4") shouldBe MimeTypeComponent.MimeType("video/mp4", null)
            }

            expect("m2ts") {
                mimeTypeComponent.guess("foo.m2ts") shouldBe MimeTypeComponent.MimeType("video/vnd.dlna.mpeg-tts", null)
            }

            expect("ts") {
                mimeTypeComponent.guess("foo.ts") shouldBe MimeTypeComponent.MimeType("video/vnd.dlna.mpeg-tts", null)
            }

            expect("gzipped m2ts") {
                mimeTypeComponent.guess("foo.m2ts.gz") shouldBe MimeTypeComponent.MimeType("video/vnd.dlna.mpeg-tts", "gzip")
            }

            expect("gzipped unknown extension") {
                mimeTypeComponent.guess("foo.unknown.gz") shouldBe MimeTypeComponent.MimeType(null, "gzip")
            }

            expect("upper case extension") {
                mimeTypeComponent.guess("FOO.MP4") shouldBe MimeTypeComponent.MimeType("video/mp4", null)
            }

            expect("subtitle and auxiliary files") {
                mimeTypeComponent.guess("foo.ass") shouldBe MimeTypeComponent.MimeType("text/x-ssa", null)
                mimeTypeComponent.guess("foo.srt") shouldBe MimeTypeComponent.MimeType("application/x-subrip", null)
                mimeTypeComponent.guess("foo.txt") shouldBe MimeTypeComponent.MimeType("text/plain", null)
                mimeTypeComponent.guess("foo.log") shouldBe MimeTypeComponent.MimeType("text/plain", null)
                mimeTypeComponent.guess("foo.json") shouldBe MimeTypeComponent.MimeType("application/json", null)
                mimeTypeComponent.guess("foo.xml") shouldBe MimeTypeComponent.MimeType("application/xml", null)
                mimeTypeComponent.guess("foo.aac") shouldBe MimeTypeComponent.MimeType("audio/aac", null)
                mimeTypeComponent.guess("foo.m4a") shouldBe MimeTypeComponent.MimeType("audio/mp4", null)
                mimeTypeComponent.guess("foo.wav") shouldBe MimeTypeComponent.MimeType("audio/wav", null)
            }

            expect("gzipped auxiliary file") {
                mimeTypeComponent.guess("foo.txt.gz") shouldBe MimeTypeComponent.MimeType("text/plain", "gzip")
            }

            expect("auxiliary files are not probeable video") {
                listOf("ass", "srt", "txt", "log", "json", "xml", "aac", "m4a", "wav").forEach {
                    mimeTypeComponent.guess("foo.$it").isProbeableVideo shouldBe false
                }
            }

            expect("unknown extension") {
                mimeTypeComponent.guess("foo.unknown") shouldBe MimeTypeComponent.MimeType(null, null)
            }

            expect("no extension") {
                mimeTypeComponent.guess("foo") shouldBe MimeTypeComponent.MimeType(null, null)
            }
        }

        context("isProbeableVideo") {
            expect("uncompressed video") {
                mimeTypeComponent.guess("foo.mp4").isProbeableVideo shouldBe true
                mimeTypeComponent.guess("foo.m2ts").isProbeableVideo shouldBe true
            }

            expect("compressed video") {
                mimeTypeComponent.guess("foo.m2ts.gz").isProbeableVideo shouldBe false
            }

            expect("non-video") {
                mimeTypeComponent.guess("foo.ass").isProbeableVideo shouldBe false
            }
        }
    })
