package me.pinfort.tsvideos.console.common.version

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

class ApplicationVersionTest :
    ExpectSpec({
        context("version") {
            expect("ビルド時に生成されたversion.propertiesから読み出せる") {
                // #139 で core から console-common へ移した後も、Gradle プロジェクトのバージョンは変わらない
                ApplicationVersion.version shouldBe "0.0.1-SNAPSHOT"
            }
        }

        context("commit") {
            expect("ビルド時のgitコミットハッシュを読み出せる") {
                ApplicationVersion.commit shouldMatch Regex("[0-9a-f]{7,40}")
            }
        }

        context("value") {
            expect("バージョンとコミットハッシュを並べた文字列になる") {
                ApplicationVersion.value shouldBe "${ApplicationVersion.version} (${ApplicationVersion.commit})"
            }
        }
    })
