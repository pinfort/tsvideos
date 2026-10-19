package me.pinfort.tsvideos.core.external.samba

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.core.config.SambaConfigurationProperties
import me.pinfort.tsvideos.core.storage.NasStore

class SambaClientTest :
    ExpectSpec({
        fun sambaClient(
            baseDir: String,
            url: String = "smb://localhost/video-store/",
        ): SambaClient {
            val server =
                SambaConfigurationProperties.Server(
                    url = url,
                    username = "user",
                    password = "password",
                    baseDir = baseDir,
                )
            return SambaClient(
                SambaConfigurationProperties(
                    videoStoreNas = server,
                    originalStoreNas = server,
                ),
            )
        }

        context("videoStoreNas") {
            expect("returns the share root regardless of baseDir") {
                sambaClient("foo/bar").videoStoreNas().locator.path shouldBe "smb://localhost/video-store/"
            }

            expect("normalizes the share root when the configured url has no trailing slash") {
                sambaClient("foo", url = "smb://localhost:139/alice").videoStoreNas().locator.path shouldBe
                    "smb://localhost:139/alice/"
            }
        }

        context("originalStoreNas") {
            expect("returns the share root regardless of baseDir") {
                sambaClient("baz").originalStoreNas().locator.path shouldBe "smb://localhost/video-store/"
            }
        }

        context("resolvePathUnderBaseDir") {
            expect("returns the relative path unchanged when baseDir is blank") {
                sambaClient("").resolvePathUnderBaseDir(NasStore.VIDEO_STORE_NAS, "bucket/program/file.mp4") shouldBe
                    "bucket/program/file.mp4"
            }

            expect("prefixes the relative path with baseDir") {
                sambaClient("foo").resolvePathUnderBaseDir(NasStore.VIDEO_STORE_NAS, "bucket/program/file.mp4") shouldBe
                    "foo/bucket/program/file.mp4"
            }

            expect("trims leading and trailing slashes from baseDir") {
                sambaClient("/foo/bar/").resolvePathUnderBaseDir(
                    NasStore.ORIGINAL_STORE_NAS,
                    "bucket/program/file.gz",
                ) shouldBe "foo/bar/bucket/program/file.gz"
            }
        }
    })
