package me.pinfort.tsvideos.manager.console.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.command.DuplicateProgramCommand
import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.domain.CreatedFile
import me.pinfort.tsvideos.core.domain.DuplicateGroup
import me.pinfort.tsvideos.core.domain.ExecutedFile
import me.pinfort.tsvideos.core.domain.ExecutedFileCheck
import me.pinfort.tsvideos.core.domain.ExecutedFileTag
import me.pinfort.tsvideos.core.domain.Program
import me.pinfort.tsvideos.core.domain.ProgramDetail
import me.pinfort.tsvideos.manager.console.parseManager
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime

class ReadOutputTest :
    ExpectSpec({
        val mapper = JsonMapper.builder().build()
        val date = LocalDateTime.of(2026, 10, 12, 1, 2, 3)
        val program = Program(9, "録画\"\n名前", 2, Program.Status.COMPLETED, 0, 9007199254740993, date, "ch", "番組", "局", 12.5)

        expect("search JSON preserves scalar types, escapes text and emits only the envelope") {
            val programs = mockk<ProgramCommand>()
            every { programs.selectByName(any(), any(), any()) } returns listOf(program)
            every { programs.hasTsFile(any()) } returns true
            val result = Search(programs, mockk()).testRead("--name 番組 --json")
            result.statusCode shouldBe 0
            result.stderr shouldBe ""
            val root = mapper.readTree(result.stdout)
            root["schemaVersion"].asInt() shouldBe 1
            root["programs"][0]["name"].asString() shouldBe program.name
            root["programs"][0]["size"].asLong() shouldBe program.size
            root["programs"][0]["duration"].asDouble() shouldBe 12.5
            root["programs"][0]["recordedAt"].asString() shouldBe "2026-10-12T01:02:03"
            root["programs"][0]["status"].asString() shouldBe "COMPLETED"
            root["programs"][0]["tsExists"].asBoolean() shouldBe true
        }

        expect("search IDs are deterministic with timestamp ties and require no extra queries") {
            val programs = mockk<ProgramCommand>()
            every { programs.selectByName(any(), any(), any()) } returns listOf(program, program.copy(id = 1))
            Search(programs, mockk()).testRead("--name 番組 --ids-only").stdout shouldBe "1\n9\n"
        }

        expect("empty lists succeed with empty arrays or no ID lines") {
            val programs = mockk<ProgramCommand>()
            every { programs.selectByName(any(), any(), any()) } returns emptyList()
            val json = Search(programs, mockk()).testRead("--name x --json")
            json.statusCode shouldBe 0
            mapper.readTree(json.stdout)["programs"].size() shouldBe 0
            Search(programs, mockk()).testRead("--name x --ids-only").stdout shouldBe ""
            val duplicates = mockk<DuplicateProgramCommand>()
            every { duplicates.detect(any()) } returns emptyList()
            val groups = Duplicates(duplicates, programs, mockk()).testRead("--json")
            groups.statusCode shouldBe 0
            mapper.readTree(groups.stdout)["groups"].size() shouldBe 0
            Duplicates(duplicates, programs, mockk()).testRead("--ids-only").stdout shouldBe ""
        }

        expect("get detail has explicit nested file fields and nullable metadata") {
            val programs = mockk<ProgramCommand>()
            every { programs.findDetail(9) } returns
                ProgramDetail(
                    9,
                    program.name,
                    2,
                    program.status,
                    0,
                    program.size,
                    date,
                    "ch",
                    "番組",
                    "局",
                    12.5,
                    listOf(CreatedFile(4, 3, "folder\\file.mp4", 500, null, null, CreatedFile.Status.FILE_MOVED)),
                    tags = listOf(ExecutedFileTag.EWS),
                    checks = listOf(ExecutedFileCheck.EMERGENCY_BROADCAST),
                )
            val result = Get(programs, mockk(), mockk()).testRead("program 9 --json")
            result.statusCode shouldBe 0
            result.stderr shouldBe ""
            val json = mapper.readTree(result.stdout)["program"]
            json["tags"].map { it.asText() } shouldBe listOf("ews")
            json["checks"].map { it.asText() } shouldBe listOf("emergency_broadcast")
            val file = json["createdFiles"][0]
            file["id"].asLong() shouldBe 4
            file["mime"].isNull shouldBe true
            file.has("isMp4") shouldBe false
            Get(programs, mockk(), mockk()).testRead("program 9 --ids-only").stdout shouldBe "9\n"
        }

        expect("executed file JSON uses its own identity and rejects program ID mode") {
            val files = mockk<ExecutedFileCommand>()
            every { files.find(2) } returns ExecutedFile(2, "録画.m2ts", 0, 20, date, "ch", "番組", "局", 12.5, ExecutedFile.Status.SPLITTED)
            val result = Get(mockk(), files, mockk()).testRead("executed_file 2 --json")
            result.statusCode shouldBe 0
            mapper.readTree(result.stdout)["executedFile"]["id"].asLong() shouldBe 2
            val invalid = Get(mockk(), files, mockk()).testRead("executed_file 2 --ids-only")
            invalid.statusCode shouldBe 2
            invalid.stdout shouldBe ""
        }

        expect("duplicates expose recommendations and emit every distinct member ID") {
            val duplicates = mockk<DuplicateProgramCommand>()
            val programs = mockk<ProgramCommand>()
            every { duplicates.detect(any()) } returns
                listOf(
                    DuplicateGroup(DuplicateGroup.Reason.SAME_BROADCAST, listOf(program, program.copy(id = 1))),
                    DuplicateGroup(DuplicateGroup.Reason.OVERLAP, listOf(program, program.copy(id = 2))),
                )
            every { programs.hasTsFile(any()) } returns false
            val result = Duplicates(duplicates, programs, mockk()).testRead("--json")
            result.statusCode shouldBe 0
            result.stderr shouldBe ""
            val group = mapper.readTree(result.stdout)["groups"][0]
            group["recommendedId"].asLong() shouldBe 9
            group["candidateIds"][0].asLong() shouldBe 1
            group["programs"].size() shouldBe 2
            Duplicates(duplicates, programs, mockk()).testRead("--ids-only").stdout shouldBe "1\n2\n9\n"
        }

        expect("missing resources and invalid input use stderr with predictable exits") {
            val programs = mockk<ProgramCommand>()
            every { programs.findDetail(9) } returns null
            val missing = Get(programs, mockk(), mockk()).testRead("program 9 --json")
            missing.statusCode shouldBe 3
            missing.stdout shouldBe ""
            missing.stderr.isNotBlank() shouldBe true
            listOf("program nope --json", "program 0 --json", "program -1 --json", "program 9 --json --ids-only").forEach {
                val invalid = Get(programs, mockk(), mockk()).testRead(it)
                invalid.statusCode shouldBe 2
                invalid.stdout shouldBe ""
                invalid.stderr.isNotBlank() shouldBe true
            }
        }

        expect("operational failures never expose driver details or partial JSON") {
            val programs = mockk<ProgramCommand>()
            every { programs.selectByName(any(), any(), any()) } throws IllegalStateException("password=secret")
            val result = Search(programs, mockk()).testRead("--name x --json")
            result.statusCode shouldBe 1
            result.stdout shouldBe ""
            result.stderr.contains("secret") shouldBe false
            result.stderr.contains("Unable to read resources") shouldBe true
        }
    })

private fun ReadCommand.testRead(arguments: String) = test(arguments.split(' ')) { parseManager(it) }
