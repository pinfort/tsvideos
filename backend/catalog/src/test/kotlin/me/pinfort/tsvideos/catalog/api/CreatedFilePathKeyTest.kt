package me.pinfort.tsvideos.catalog.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CreatedFilePathKeyTest :
    ExpectSpec({
        val base = "base/a/alpha/video.mp4"

        expect("SMB aliases share one key: case, either separator and trailing dots or spaces of any segment") {
            for (alias in listOf(
                "BASE/A/ALPHA/VIDEO.MP4",
                "base\\a\\alpha\\video.mp4",
                "Base\\A/Alpha\\Video.Mp4",
                "base./a /alpha. . /video.mp4.",
                "base/a/alpha/video.mp4 . ",
            )) {
                CreatedFilePathKey.of(alias) shouldBe base
                CreatedFilePathKey.hash(alias).toList() shouldBe CreatedFilePathKey.hash(base).toList()
            }
        }

        expect("Unicode case and composition variants share one key") {
            // Composed and decomposed forms, and characters whose upper case folds into ASCII.
            CreatedFilePathKey.of("base/a/café/v.mp4") shouldBe CreatedFilePathKey.of("base/a/café/v.mp4")
            CreatedFilePathKey.of("base/a/CAFÉ/v.mp4") shouldBe CreatedFilePathKey.of("base/a/café/v.mp4")
            CreatedFilePathKey.of("base/a/ſun/v.mp4") shouldBe CreatedFilePathKey.of("base/a/sun/v.mp4")
            CreatedFilePathKey.of("base/a/Key/v.mp4") shouldBe CreatedFilePathKey.of("base/a/key/v.mp4")
            CreatedFilePathKey.of("base/a/Ｂeta/v.mp4") shouldBe CreatedFilePathKey.of("base/a/ｂETA/v.mp4")
        }

        expect("different files keep different keys, and only trailing characters are trimmed") {
            for (other in listOf(
                "base/a/alpha/video2.mp4",
                "base/a/alpha/videomp4",
                "base/a/alpha/.video.mp4",
                "base/a/alpha/ video.mp4",
                "base/a/beta/video.mp4",
                "base/a/alpha/video.mp4/x",
            )) {
                CreatedFilePathKey.of(other) shouldNotBe base
            }
        }

        expect("long paths hash to a fixed-size key") {
            val long = "base/" + "segment/".repeat(2000) + "video.mp4"
            CreatedFilePathKey.hash(long).size shouldBe 32
            CreatedFilePathKey.hash(long).toList() shouldBe CreatedFilePathKey.hash(long.uppercase()).toList()
        }
    })
