package me.pinfort.tsvideos.processing.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.processing.api.MimeTypeComponent.MimeType

class MimeTypeComponentTest :
    ExpectSpec({
        val mimeTypeComponent = MimeTypeComponent()

        context("guess") {
            withData(
                nameFn = { (fileName, _) -> fileName },
                "foo.mp4" to MimeType("video/mp4", null),
                "foo.m2ts" to MimeType("video/vnd.dlna.mpeg-tts", null),
                "foo.ts" to MimeType("video/vnd.dlna.mpeg-tts", null),
                "foo.m2ts.gz" to MimeType("video/vnd.dlna.mpeg-tts", "gzip"),
                "foo.unknown.gz" to MimeType(null, "gzip"),
                "FOO.MP4" to MimeType("video/mp4", null),
                "foo.ass" to MimeType("text/x-ssa", null),
                "foo.srt" to MimeType("application/x-subrip", null),
                "foo.txt" to MimeType("text/plain", null),
                "foo.log" to MimeType("text/plain", null),
                "foo.json" to MimeType("application/json", null),
                "foo.xml" to MimeType("application/xml", null),
                "foo.aac" to MimeType("audio/aac", null),
                "foo.m4a" to MimeType("audio/mp4", null),
                "foo.wav" to MimeType("audio/wav", null),
                "foo.txt.gz" to MimeType("text/plain", "gzip"),
                "foo.unknown" to MimeType(null, null),
                "foo" to MimeType(null, null),
            ) { (fileName, expected) ->
                mimeTypeComponent.guess(fileName) shouldBe expected
            }
        }

        context("isProbeableVideo") {
            withData(
                nameFn = { (fileName, probeable) -> "$fileName -> $probeable" },
                "foo.mp4" to true,
                "foo.m2ts" to true,
                "foo.m2ts.gz" to false,
                "foo.ass" to false,
                "foo.srt" to false,
                "foo.txt" to false,
                "foo.log" to false,
                "foo.json" to false,
                "foo.xml" to false,
                "foo.aac" to false,
                "foo.m4a" to false,
                "foo.wav" to false,
            ) { (fileName, probeable) ->
                mimeTypeComponent.guess(fileName).isProbeableVideo shouldBe probeable
            }
        }
    })
