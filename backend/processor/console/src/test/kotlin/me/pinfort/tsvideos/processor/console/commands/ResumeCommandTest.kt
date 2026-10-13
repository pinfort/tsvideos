package me.pinfort.tsvideos.processor.console.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.domain.ResumeJobMissing
import me.pinfort.tsvideos.core.domain.ResumePlan
import me.pinfort.tsvideos.core.domain.ResumeStagePlan
import me.pinfort.tsvideos.processor.console.parseProcessor
import me.pinfort.tsvideos.processor.infrastructure.pipeline.ProcessingResumeRunner
import tools.jackson.databind.json.JsonMapper

class ResumeCommandTest :
    ExpectSpec({
        expect("dry-run JSON reports stage actions and blocked prerequisites with exit four") {
            val runner = mockk<ProcessingResumeRunner>()
            every { runner.run(7, true) } returns
                ResumePlan(
                    7,
                    "PROCESS",
                    listOf(ResumeStagePlan("SPLIT", "BLOCKED", "LOCAL_ARTIFACT_MISSING", listOf("Restore unchanged artifact"))),
                )
            val result = ResumeCommand(runner).invoke(listOf("--job-id", "7", "--dry-run", "--json"))
            result.statusCode shouldBe 4
            val json = JsonMapper.builder().build().readTree(result.stdout)
            json["schemaVersion"].asInt() shouldBe 1
            json["dryRun"].asBoolean() shouldBe true
            json["resume"]["stages"][0]["action"].asString() shouldBe "BLOCKED"
        }
        expect("invalid unknown operational and interrupted requests have stable safe codes") {
            val runner = mockk<ProcessingResumeRunner>()
            ResumeCommand(runner).invoke(emptyList()).statusCode shouldBe 2
            ResumeCommand(runner).invoke(listOf("--job-id", "0")).statusCode shouldBe 2
            every { runner.run(1, false) } throws ResumeJobMissing()
            ResumeCommand(runner).invoke(listOf("--job-id", "1")).statusCode shouldBe 3
            every { runner.run(1, false) } throws java.io.IOException("secret-password")
            val result = ResumeCommand(runner).invoke(listOf("--job-id", "1"))
            result.statusCode shouldBe 1
            result.stderr.contains("secret-password") shouldBe false
            every { runner.run(1, false) } throws InterruptedException()
            try {
                ResumeCommand(runner).invoke(listOf("--job-id", "1")).statusCode shouldBe 130
            } finally {
                Thread.interrupted()
            }
        }
    })

private fun ResumeCommand.invoke(args: List<String>) = test(args) { parseProcessor(it) }
