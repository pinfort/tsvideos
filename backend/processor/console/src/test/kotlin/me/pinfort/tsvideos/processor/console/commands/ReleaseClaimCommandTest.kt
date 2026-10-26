package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.core.main
import io.kotest.core.spec.style.ExpectSpec
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.console.common.component.UserQuestionComponent
import me.pinfort.tsvideos.processing.api.ProcessingJobCommand
import me.pinfort.tsvideos.processing.api.VerifiedUploadCommand
import me.pinfort.tsvideos.processing.domain.ProcessingJob
import me.pinfort.tsvideos.processing.domain.UploadManifest
import me.pinfort.tsvideos.storage.api.NasStore
import java.io.File

class ReleaseClaimCommandTest :
    ExpectSpec({
        lateinit var jobs: ProcessingJobCommand
        lateinit var uploads: VerifiedUploadCommand
        lateinit var question: UserQuestionComponent
        lateinit var command: ReleaseClaimCommand
        val path = File("/rec/rec.m2ts").canonicalPath
        val job = ProcessingJob(7, "key", path, 1, 1, ProcessingJob.State.CLAIMED, 10, null)
        val stale =
            UploadManifest(
                3,
                7,
                "artifact",
                "destination",
                NasStore.ORIGINAL_STORE_NAS,
                "base/r/rec/rec_1.m2ts.gz",
                "base/r/rec/rec_1.m2ts.gz.upload-x",
                "/rec/tssplitter/rec_1.m2ts.gz",
                1,
                1,
                "sha",
                false,
                UploadManifest.State.TRANSFERRING,
                "dead-owner",
                null,
            )

        beforeTest {
            clearAllMocks()
            jobs = mockk()
            uploads = mockk()
            question = mockk()
            command = ReleaseClaimCommand(jobs, uploads, question)
            every { jobs.findByPath(path) } returns job
            every { uploads.claimed(7) } returns listOf(stale)
            every { uploads.releaseAbandonedClaim(any()) } just Runs
        }

        context("run") {
            expect("releases exactly the inspected claims when the operator confirms") {
                every { question.askDefaultFalse(any()) } returns true

                command.main(arrayOf(path))

                verify(exactly = 1) { uploads.releaseAbandonedClaim(stale) }
            }

            expect("releases nothing when the operator declines") {
                every { question.askDefaultFalse(any()) } returns false

                command.main(arrayOf(path))

                verify(exactly = 0) { uploads.releaseAbandonedClaim(any()) }
            }

            expect("dry run only lists the claims") {
                command.main(arrayOf("--dry-run", path))

                verify(exactly = 0) { question.askDefaultFalse(any()) }
                verify(exactly = 0) { uploads.releaseAbandonedClaim(any()) }
            }

            expect("an unknown recording or no claims asks nothing") {
                every { uploads.claimed(7) } returns emptyList()
                command.main(arrayOf(path))
                every { jobs.findByPath(path) } returns null
                command.main(arrayOf(path))

                verify(exactly = 0) { question.askDefaultFalse(any()) }
                verify(exactly = 0) { uploads.releaseAbandonedClaim(any()) }
            }
        }
    })
